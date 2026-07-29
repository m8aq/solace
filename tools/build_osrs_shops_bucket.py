#!/usr/bin/env python3
"""
Build the shops dataset from Bucket instead of from wikitext.

A prototype, to see what changes if extraction moves to the wiki's own
structured store. build_osrs_shops.py reads `{{Infobox Shop}}` and
`{{StoreLine}}` out of wikitext, which means naming both templates, tracking
their parameter spellings, and re-deriving item ids by matching names against
all-items.json. Every one of those was a source of bugs: `facilities` read as
`facility`, `{{NPC map}}` missed because it is an alias, ids invented from
`hist1`.

Bucket already holds what those templates wrote. `infobox_shop` is the shop
itself and `storeline` is its stock, joined to `infobox_item` for the ids, all
server-side. No template names, no name-matching, and the ids come from the
same place the wiki got them.

What Bucket does not do is merge. There is no groupBy, so collapsing a shop
that appears on both an npc page and its own shop page still happens here --
which is fine, because that logic is the part worth keeping.

Usage:
    python3 tools/build_osrs_shops_bucket.py --out /tmp/shops-bucket.json
    python3 tools/build_osrs_shops_bucket.py --compare data/wiki/all-shops.json
"""

import argparse
import json
from pathlib import Path

from osrs_wiki import api_get, load_dataset, log

PAGE = 2500


def bucket(query):
    """Run one Bucket query, following limit/offset until it runs dry."""
    rows, offset = [], 0
    while True:
        page = api_get({"action": "bucket",
                        "query": query.format(limit=PAGE, offset=offset)})
        got = page.get("bucket") or []
        rows += got
        if len(got) < PAGE:
            return rows
        offset += PAGE


def fetch_shops():
    return bucket(
        'bucket("infobox_shop")'
        '.select("page_name","shop_name","shop_version","specialty",'
        '"location","owner","is_members_only")'
        '.limit({limit}).offset({offset}).run()')


def fetch_stock():
    """Stock rows joined to the item table, so ids arrive with the prices."""
    return bucket(
        'bucket("storeline")'
        '.join("infobox_item","storeline.sold_item","infobox_item.item_name")'
        '.select("storeline.sold_by","storeline.sold_item",'
        '"storeline.store_sell_price","storeline.store_buy_price",'
        '"storeline.store_stock","storeline.restock_time",'
        '"storeline.store_currency","infobox_item.item_id")'
        '.limit({limit}).offset({offset}).run()')


def first(value):
    """Bucket returns repeated fields as lists; take the first."""
    if isinstance(value, list):
        return value[0] if value else None
    return value


def number(value):
    try:
        return int(str(first(value)).replace(",", ""))
    except (TypeError, ValueError):
        return None


def fetch_items():
    """Every item name and id, for the stock lines the join misses."""
    return bucket('bucket("infobox_item").select("item_name","item_id")'
                  '.limit({limit}).offset({offset}).run()')


def build():
    shops, stock = fetch_shops(), fetch_stock()
    items = fetch_items()
    log(f"bucket: {len(shops)} shops, {len(stock)} stock rows, "
        f"{len(items)} items")

    # `sold_item` does not always equal `item_name` -- a shop may sell
    # "Ore pack (Giants' Foundry)" where the item page is "Ore pack" -- so the
    # join alone leaves ids null. Fall back to a normalised name, then to the
    # name with any parenthesised qualifier removed.
    by_name, by_base = {}, {}
    for row in items:
        name = (first(row.get("item_name")) or "").strip()
        ident = number(row.get("item_id"))
        if not name or ident is None:
            continue
        by_name.setdefault(name.lower(), ident)
        base = name.split(" (")[0].strip().lower()
        by_base.setdefault(base, ident)

    def resolve(row):
        ident = number(row.get("infobox_item.item_id"))
        if ident is not None:
            return ident
        name = (row.get("storeline.sold_item") or "").strip().lower()
        return by_name.get(name) or by_base.get(name.split(" (")[0].strip())

    by_owner = {}
    for row in stock:
        by_owner.setdefault(row["storeline.sold_by"], []).append(row)

    # Anchor on who actually sells something. infobox_shop covers only 452
    # pages where 586 sell stock, because a shop documented on an npc page
    # often carries no shop infobox.
    meta = {s["page_name"]: s for s in shops}
    out = []
    for page in sorted(set(by_owner) | set(meta)):
        shop = meta.get(page, {"page_name": page})
        inventory = []
        for row in by_owner.get(page, []):
            inventory.append({
                "name": row["storeline.sold_item"],
                "id": resolve(row),
                "baseQuantity": number(row.get("storeline.store_stock")),
                "restockTime": first(row.get("storeline.restock_time")),
                "sellPrice": number(row.get("storeline.store_sell_price")),
                "buyPrice": number(row.get("storeline.store_buy_price")),
            })
        out.append({
            "name": first(shop.get("shop_name")) or page,
            "page": page,
            "location": first(shop.get("location")),
            "owner": first(shop.get("owner")),
            "specialty": first(shop.get("specialty")),
            "isMembers": bool(first(shop.get("is_members_only"))),
            "shopVersion": first(shop.get("shop_version")),
            "inventory": inventory,
        })
    out.sort(key=lambda s: (s["page"], s["name"]))
    return out


def compare(built, existing_path):
    """Where the two disagree, and on what."""
    old = load_dataset(existing_path)
    old_by = {s["page"]: s for s in old}
    new_by = {s["page"]: s for s in built}

    log(f"\n{'':<22}{'wikitext':>10}{'bucket':>9}")
    log(f"  {'shops':<20}{len(old):>10}{len(built):>9}")
    log(f"  {'pages':<20}{len(old_by):>10}{len(new_by):>9}")
    log(f"  {'stock lines':<20}"
        f"{sum(len(s.get('inventory') or []) for s in old):>10}"
        f"{sum(len(s['inventory']) for s in built):>9}")

    only_old = sorted(set(old_by) - set(new_by))
    only_new = sorted(set(new_by) - set(old_by))
    log(f"\n  pages only in wikitext: {len(only_old)}  {only_old[:5]}")
    log(f"  pages only in bucket  : {len(only_new)}  {only_new[:5]}")

    # where both have the shop, do the stock lists agree?
    same = agree = 0
    id_gap = []
    for page in set(old_by) & set(new_by):
        same += 1
        a = {i.get("name") for i in (old_by[page].get("inventory") or [])}
        b = {i["name"] for i in new_by[page]["inventory"]}
        if a == b:
            agree += 1
        for item in new_by[page]["inventory"]:
            if item["id"] is None:
                id_gap.append((page, item["name"]))
    log(f"\n  shared pages: {same}, identical stock lists: {agree} "
        f"({100 * agree // max(same, 1)}%)")
    log(f"  bucket stock lines with no item id: {len(id_gap)}"
        f"  {id_gap[:3]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out")
    ap.add_argument("--compare", help="an existing all-shops.json to diff against")
    args = ap.parse_args()

    built = build()
    if args.out:
        Path(args.out).write_text(json.dumps(built, indent=1))
        log(f"wrote {args.out}  ({len(built)} shops)")
    if args.compare:
        compare(built, args.compare)


if __name__ == "__main__":
    main()
