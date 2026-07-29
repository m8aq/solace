#!/usr/bin/env python3
"""
Build all-shops.json from the live OSRS wiki.

A shop is a {{StoreTableHead}} followed by its {{StoreLine}} rows. Both
dedicated shop pages and NPC shopkeeper pages carry them, and a page may hold
several shops, so pages are walked in document order rather than by template
name -- see iter_templates in osrs_wiki.py.

Dedicated shop pages add {{Infobox Shop}} with name, location, owner and a map;
NPC pages have none of that, so the page title becomes the shop name and the
owner is the page's own NPC.

Owner names are resolved to NPC ids against all-npcs.json, and with --link-npcs
(the default) that file is patched in place with a `shopNames` back-reference.
Run this after build_osrs_npcs.py.

Usage:
    python3 tools/build_osrs_shops.py --out data/wiki
    python3 tools/build_osrs_shops.py --out data/wiki --no-link-npcs
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

from build_osrs_items import item_lookup
# the back-reference patch rewrites all-npcs.json, so it has to re-apply that
# file's own output shape rather than dumping a plain array over it
from build_osrs_npcs import write_shape as npc_write_shape
from osrs_wiki import (
    wiki_title,
    load_dataset,
    fetch_wikitext, find_templates, iter_templates, log, pages_embedding,
    parse_map_features, progress_bar, strip_markup, template_params, versioned,
    wiki_bool, wiki_int,
)

SHOP_TEMPLATES = ("StoreTableHead", "StoreLine", "StoreTableBottom")

# stock/restock markers meaning "never runs out" rather than a quantity
INFINITE = {"inf", "infinite", "∞", "unlimited"}


def _percent(raw):
    """`sellmultiplier=1000` is per-mille, so 1000 -> 1.0. None when absent.

    Deliberately does not invent a default: a missing multiplier means the wiki
    did not state one, and guessing produced buyPercent 0.6 for 294 shops that
    do not buy anything at all.
    """
    val = wiki_int(raw)
    return None if val is None else val / 1000


def _buy_percent(params):
    """Buy rate, treating `hidebuy` as the shop refusing to buy.

    A pub states `hidebuy=y` and omits `buymultiplier` -- the buy column is
    hidden because there is nothing to show. Its NPC-page twin spells the same
    fact out as `buymultiplier=0`, which is how the two disagreed.
    """
    stated = _percent(params.get("buymultiplier"))
    if stated is not None:
        return stated
    return 0.0 if "hidebuy" in params else None


def parse_inventory_row(block, lookup):
    """{{StoreLine}} -> one inventory entry."""
    p = template_params(block)
    name = strip_markup(p.get("name", ""))
    if not name:
        return None

    row = {"name": name}
    # `bucketname` disambiguates a variant, e.g. "Bailing bucket#Empty";
    # item_lookup already strips the anchor
    iid = lookup(p.get("bucketname") or name) if lookup else None
    if iid is not None:
        row["id"] = iid
    display = strip_markup(p.get("displayname", ""))
    if display and display != name:
        row["displayName"] = display

    stock = (p.get("stock") or "").strip().lower()
    if stock in INFINITE:
        row["baseQuantity"] = None
        row["isInfiniteStock"] = True
    else:
        row["baseQuantity"] = wiki_int(p.get("stock"))
    row["restockTime"] = wiki_int(p.get("restock"))

    # explicit per-item price overrides, where a shop ignores the multipliers
    for key, out in (("buy", "buyPrice"), ("sell", "sellPrice")):
        val = wiki_int(p.get(key))
        if val is not None:
            row[out] = val
    return row


def parse_shop_page(title, text, lookup):
    """Every shop on a page, in document order."""
    # a page's Infobox Shop describes its headline shop; NPC pages have none
    meta = {}
    for block in find_templates(text, "Infobox Shop"):
        p = template_params(block)
        v = versioned(p, "")
        meta = {
            "name": strip_markup(v("name") or title),
            "location": strip_markup(v("location", "")) or None,
            "owner": strip_markup(v("owner", "")) or None,
            "specialty": wiki_title(strip_markup(v("special", ""))),
            "isMembers": wiki_bool(v("members")),
            "leagueRegion": wiki_title(strip_markup(v("leagueregion", ""))),
            "spawns": parse_map_features(v("map", "")),
        }
        break

    shops, current = [], None
    for name, block in iter_templates(text, SHOP_TEMPLATES):
        if name == "StoreTableHead":
            p = template_params(block)
            current = {
                # the first shop on a page inherits the infobox identity; any
                # further ones are distinguished by index
                "name": meta.get("name") or title,
                "page": title,
                "index": len(shops),
                "location": strip_markup(p.get("location", ""))
                            or meta.get("location"),
                "owner": meta.get("owner"),
                "ownerNpcIds": [],
                "specialty": meta.get("specialty"),
                "isMembers": (wiki_bool(p["members"]) if "members" in p
                              else meta.get("isMembers", False)),
                "leagueRegion": meta.get("leagueRegion"),
                "currency": wiki_title(strip_markup(p.get("currency")
                                         or p.get("currency1") or "")),
                "sellPercent": _percent(p.get("sellmultiplier")),
                "buyPercent": _buy_percent(p),
                "buyChangePercent": _percent(p.get("delta")),
                "shopVersion": strip_markup(p.get("shopversion", "")) or None,
                "spawns": meta.get("spawns", []) if not shops else [],
                "inventory": [],
            }
            shops.append(current)
        elif name == "StoreLine" and current is not None:
            row = parse_inventory_row(block, lookup)
            if row:
                current["inventory"].append(row)
        elif name == "StoreTableBottom":
            current = None
    return shops


def npc_index(npcs):
    """Lookups from NPC name and page title to the full records behind them."""
    by_name, by_page = {}, {}
    for n in npcs:
        by_name.setdefault(n["name"].lower(), []).append(n)
        by_page.setdefault(n["page"].lower(), []).append(n)
    return by_name, by_page


def _narrow_by_location(candidates, location):
    """Keep candidates whose own location or page matches the shop's.

    Generic owner names ("Bartender", "Shop keeper") are shared by dozens of
    NPCs, so a name match alone would hand every pub in the game the same 23
    owners. The shop's location is what separates them.
    """
    loc = (location or "").strip().lower()
    if not loc or len(candidates) < 2:
        return candidates
    hits = [c for c in candidates
            if loc in (c.get("location") or "").lower()
            or loc in c["page"].lower()
            or ((c.get("location") or "").lower() or None) in (loc,)]
    return hits or candidates


# A shopkeeper stands in their shop, so the owner's spawn is within a building's
# distance of the shop's own map marker. Beyond this the match is coincidence.
OWNER_MAX_TILES = 20


def _narrow_by_proximity(candidates, shop_spawns):
    """Keep the candidate standing closest to the shop, if one clearly is.

    Location alone cannot separate three Varrock bartenders; their coordinates
    can. Only decides when it produces a single candidate inside the radius.
    """
    if len(candidates) < 2 or not shop_spawns:
        return candidates

    def nearest(npc):
        best = None
        for a in npc.get("spawns") or []:
            for b in shop_spawns:
                if a.get("plane") != b.get("plane"):
                    continue
                d = max(abs(a["x"] - b["x"]), abs(a["y"] - b["y"]))
                best = d if best is None else min(best, d)
        return best

    near = [(nearest(c), c) for c in candidates]
    inside = [(d, c) for d, c in near if d is not None and d <= OWNER_MAX_TILES]
    if len(inside) == 1:
        return [inside[0][1]]
    if len(inside) > 1:
        # several inside the radius: take the closest if it stands out
        inside.sort(key=lambda dc: dc[0])
        if inside[0][0] * 2 < inside[1][0]:
            return [inside[0][1]]
    return candidates


def resolve_owners(shop, by_name, by_page):
    """Owner names -> NPC ids, in place.

    `owner` may list several NPCs ("Lucy, Megan") and may carry a parenthetical
    qualifier ("Carl Roy (After Sins of the Father)"). For a shop that lives on
    an NPC's own page, that page is the owner.
    """
    ids, ambiguous = [], False
    for raw in (shop.get("owner") or "").split(","):
        key = raw.strip().lower()
        if not key:
            continue
        cands = by_name.get(key) or by_page.get(key) or []
        if not cands:
            # retry without a trailing "(qualifier)"
            bare = re.sub(r"\s*\(.*\)$", "", key).strip()
            cands = by_name.get(bare) or by_page.get(bare) or []
        narrowed = _narrow_by_location(cands, shop.get("location"))
        narrowed = _narrow_by_proximity(narrowed, shop.get("spawns"))
        # a name that still maps to several NPCs is a guess, not a link
        if len(narrowed) > 1:
            ambiguous = True
        ids += [c["id"] for c in narrowed]

    if not ids:
        # a shop table sitting on an NPC page: the page itself is the owner
        ids += [c["id"] for c in by_page.get(shop["page"].lower(), [])]
        ambiguous = False

    shop["ownerNpcIds"] = sorted(set(ids))
    if ambiguous:
        shop["ownerAmbiguous"] = True


# The multipliers that decide whether two records describe the same shop state.
PRICE_FIELDS = ("sellPercent", "buyPercent", "buyChangePercent")


def _inventory_key(shop):
    return json.dumps(sorted(
        (str(r.get("id")), str(r.get("name")), str(r.get("baseQuantity")),
         str(r.get("restockTime"))) for r in shop["inventory"]))


def _prices_compatible(a, b):
    """Null means the page did not state a rate, so it agrees with anything.

    Comparing only stated values is not enough: the Blue Moon Inn shop page
    omits `sellPercent` while its NPC page says 1.0, and a strict compare would
    read that as two different shops rather than one described twice.
    """
    return all(a[f] is None or b[f] is None or a[f] == b[f]
               for f in PRICE_FIELDS)


def _merge_records(records):
    """Fold same-shop records into one, preferring stated values."""
    merged = dict(records[0])
    merged["pages"] = []
    for rec in records:
        for key, value in rec.items():
            if key in ("pages", "index"):
                continue
            # a stated value always beats an unstated one
            if merged.get(key) in (None, "", [], False) and value not in (None, ""):
                merged[key] = value
        if rec["page"] not in merged["pages"]:
            merged["pages"].append(rec["page"])
    # the richest name is the dedicated shop page's, which also has a location
    named = [r for r in records if r.get("location") or r.get("specialty")]
    if named:
        merged["name"] = named[0]["name"]
        merged["page"] = named[0]["page"]
    return merged


def merge_shops(shops):
    """Collapse a shop described on both its NPC page and its shop page.

    Only records with an unambiguous owner take part -- a guessed owner is not
    evidence that two shops are the same one. Within a candidate group, records
    are bucketed by price compatibility so a shop with two genuine price tiers
    (Davon's pre/post-quest rates) stays as two records instead of silently
    losing one.
    """
    groups, standalone = {}, []
    for shop in shops:
        if not shop["ownerNpcIds"] or shop.get("ownerAmbiguous"):
            standalone.append(shop)
            continue
        key = (_inventory_key(shop), tuple(shop["ownerNpcIds"]))
        groups.setdefault(key, []).append(shop)

    out, merged_groups, variant_groups = list(standalone), 0, 0
    for members in groups.values():
        buckets = []
        for shop in members:
            for bucket in buckets:
                if all(_prices_compatible(shop, other) for other in bucket):
                    bucket.append(shop)
                    break
            else:
                buckets.append([shop])
        if len(members) > 1:
            merged_groups += len(buckets) == 1
            variant_groups += len(buckets) > 1
        out += [_merge_records(b) for b in buckets]

    out.sort(key=lambda s: (s["page"], s["name"]))
    # `index` distinguished several shops on one page; renumber after merging
    seen = {}
    for shop in out:
        seen[shop["page"]] = seen.get(shop["page"], -1) + 1
        shop["index"] = seen[shop["page"]]
        shop.setdefault("pages", [shop["page"]])
    log(f"  merged {merged_groups} duplicate groups, "
        f"kept {variant_groups} as price variants")
    return out


def build_shops(lookup, npcs):
    by_name, by_page = npc_index(npcs) if npcs else ({}, {})
    titles = pages_embedding("Template:StoreLine")
    log(f"shop pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("shops"))

    shops = []
    for title, text in texts.items():
        for shop in parse_shop_page(title, text, lookup):
            if npcs:
                resolve_owners(shop, by_name, by_page)
            shops.append(shop)
    shops.sort(key=lambda s: (s["page"], s["index"]))
    return shops


def link_npcs(npcs, shops):
    """Add a `shopNames` back-reference to each owning NPC. Returns the count."""
    by_id = {}
    for shop in shops:
        for npc_id in shop["ownerNpcIds"]:
            by_id.setdefault(npc_id, []).append(shop["name"])
    touched = 0
    for npc in npcs:
        names = by_id.get(npc["id"])
        npc["shopNames"] = sorted(set(names)) if names else []
        if names:
            touched += 1
    return touched


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    ap.add_argument("--items", help="all-items.json path "
                                    "(default: <out>/all-items.json)")
    ap.add_argument("--npcs", help="all-npcs.json path "
                                   "(default: <out>/all-npcs.json)")
    ap.add_argument("--no-merge", action="store_true",
                    help="keep one record per wiki page instead of merging "
                         "shops described on both an NPC and a shop page")
    ap.add_argument("--no-link-npcs", action="store_true",
                    help="do not write shopNames back into all-npcs.json")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    items_path = Path(args.items) if args.items else out / "all-items.json"
    if not items_path.exists():
        sys.exit(f"{items_path} not found — run build_osrs_items.py first "
                 f"(stock names are resolved against it)")
    items = load_dataset(items_path)
    lookup = item_lookup(items)
    log(f"loaded {len(items)} items for stock lookup")

    npcs_path = Path(args.npcs) if args.npcs else out / "all-npcs.json"
    npcs = None
    if npcs_path.exists():
        npcs = load_dataset(npcs_path)
        log(f"loaded {len(npcs)} npcs for owner lookup")
    else:
        log(f"note: {npcs_path} not found; owners keep names only")

    t0 = time.time()
    shops = build_shops(lookup, npcs)
    raw_count = len(shops)
    if not args.no_merge:
        shops = merge_shops(shops)
        log(f"  {raw_count} -> {len(shops)} shops after merge")
    path = out / "all-shops.json"
    path.write_text(json.dumps(shops, indent=1))

    rows = sum(len(s["inventory"]) for s in shops)
    unresolved = sum(1 for s in shops for r in s["inventory"] if "id" not in r)
    owned = sum(1 for s in shops if s["ownerNpcIds"])
    log(f"wrote {path}  ({len(shops)} shops, {time.time() - t0:.0f}s)")
    log(f"  {rows} inventory rows ({unresolved} unresolved names), "
        f"{owned} shops with an owner NPC")

    if npcs and not args.no_link_npcs:
        touched = link_npcs(npcs, shops)
        was_compact = isinstance(json.loads(npcs_path.read_text()), dict)
        npcs_path.write_text(
            json.dumps(npc_write_shape(npcs, flat=not was_compact), indent=1))
        log(f"  patched {npcs_path}: shopNames on {touched} npcs")


if __name__ == "__main__":
    main()
