#!/usr/bin/env python3
"""
Build all-items.json and all-recipes.json from the live OSRS wiki.

Items come from {{Infobox Item}} (~12,400 pages), recipes from {{Recipe}}
(~3,700 pages). Recipe materials are named on the wiki, so items are built
first and used as a name -> id lookup. GE buy limits and alch values are not in
the infobox and come from the wiki's price API.

Usage:
    python3 tools/build_osrs_items.py --out data/wiki
    python3 tools/build_osrs_items.py --out data/wiki --only recipes
"""

import argparse
import collections
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

from osrs_wiki import (
    wiki_title,
    parse_ids,
    UA, compact_by_page, fetch_redirects, fetch_wikitext, find_templates, log,
    pages_embedding, parse_item_spawns, progress_bar, strip_markup,
    template_params, template_positionals, versioned, wiki_bool, wiki_file,
    wiki_int, wiki_list, wiki_num,
)

PRICES_API = "https://prices.runescape.wiki/api/v1/osrs/mapping"


# ------------------------------------------------------------------- items

def parse_item_page(title, text):
    """{{Infobox Item}} -> [{id, name, ...}], one record per variant id."""
    rows = []
    for block in find_templates(text, "Infobox Item"):
        p = template_params(block)
        idxs = sorted({m.group(1) for k in p
                       for m in [re.fullmatch(r"id(\d*)", k)] if m})
        for idx in idxs:
            v = versioned(p, idx)
            # an id field may hold a comma/newline separated list
            for one in parse_ids(p.get(f"id{idx}")):
                rows.append({
                    "id": one,
                    # `name =` is sometimes present but blank on technical
                    # pages (interface and animation items); fall back as if
                    # it were absent rather than emitting an empty string
                    "name": strip_markup(v("name") or "") or title,
                    "page": title,
                    "version": strip_markup(v("version", "")) or None,
                    "examine": strip_markup(v("examine", "")),
                    "isMembers": wiki_bool(v("members")),
                    "value": wiki_int(v("value"), 0),
                    "weight": wiki_num(v("weight")),
                    "quest": strip_markup(v("quest", "")) or None,
                    "isStackable": wiki_bool(v("stackable")),
                    "isTradeable": wiki_bool(v("tradeable")),
                    "isEquipable": wiki_bool(v("equipable")),
                    "isNoteable": wiki_bool(v("noteable")),
                    "isAlchable": wiki_bool(v("alchable", "Yes")),
                    "isBankable": wiki_bool(v("bankable", "Yes")),
                    "isEdible": wiki_bool(v("edible")),
                    "isPlaceholder": wiki_bool(v("placeholder")),
                    "isOnGrandExchange": wiki_bool(v("exchange")),
                    "stacksInBank": wiki_bool(v("stacksinbank", "Yes")),
                    "options": wiki_list(v("options")),
                    "wornOptions": wiki_list(v("wornoptions")),
                    "respawnTime": wiki_int(v("respawn")) or None,
                    "buyLimit": wiki_int(v("buylimit")) or None,
                    "geName": strip_markup(v("gemwname", "")) or None,
                    "image": wiki_file(v("image")),
                    "release": strip_markup(v("release", "")) or None,
                    "releaseUpdate": strip_markup(v("update", "")) or None,
                    "destroy": strip_markup(v("destroy", "")) or None,
                    "leagueRegion": wiki_title(strip_markup(v("leagueregion", ""))),
                    "removal": strip_markup(v("removal", "")) or None,
                    "removalUpdate": strip_markup(v("removalupdate", "")) or None,
                    "aka": wiki_list(v("aka")),
                })
    return rows


BONUS_KEYS = {
    "astab": "attackStab", "aslash": "attackSlash", "acrush": "attackCrush",
    "amagic": "attackMagic", "arange": "attackRanged",
    "dstab": "defenceStab", "dslash": "defenceSlash", "dcrush": "defenceCrush",
    "dmagic": "defenceMagic", "drange": "defenceRanged",
    "str": "meleeStrength", "rstr": "rangedStrength", "mdmg": "magicDamage",
    "prayer": "prayer", "slot": "slot", "speed": "attackSpeed",
    "attackrange": "attackRange", "combatstyle": "combatStyle",
}


def parse_bonuses(text):
    """{{Infobox Bonuses}} -> equipment stats for an equipable item."""
    for block in find_templates(text, "Infobox Bonuses"):
        p = template_params(block)
        stats = {}
        for wk, out_key in BONUS_KEYS.items():
            raw = p.get(wk, p.get(f"{wk}1"))
            if raw is None:
                continue
            if out_key in ("slot", "combatStyle"):
                s = wiki_title(strip_markup(raw))
                if s:
                    stats[out_key] = s
            else:
                stats[out_key] = wiki_num(raw)
        if stats:
            return stats
    return None


def parse_combat_styles(text):
    """{{CombatStyles|Whip|speed=4|attackrange=1}} -> weapon style block.

    The style name is the first unnamed argument and is what the game calls the
    weapon class ("Whip", "Crossbow", "Stab Sword"); speed/range are optional
    overrides of the class default.
    """
    for block in find_templates(text, "CombatStyles"):
        pos = template_positionals(block)
        if not pos:
            continue
        p = template_params(block)
        style = {"style": wiki_title(strip_markup(pos[0]))}
        for key, out_key in (("speed", "attackSpeed"),
                             ("attackrange", "attackRange")):
            val = wiki_int(p.get(key))
            if val is not None:
                style[out_key] = val
        return style
    return None


def fetch_ge_mapping():
    """GE buy limits and alch values, keyed by item id."""
    req = urllib.request.Request(PRICES_API, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            rows = json.load(r)
    except Exception as e:
        log(f"  warn: GE mapping unavailable ({e}); buyLimit/alch omitted")
        return {}
    return {x["id"]: x for x in rows if "id" in x}


def build_items(with_aliases=True):
    titles = pages_embedding("Template:Infobox Item")
    log(f"item pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("items"))

    aliases = {}
    if with_aliases:
        aliases = fetch_redirects(titles, progress=progress_bar("aliases"))

    ge = fetch_ge_mapping()
    log(f"GE mapping: {len(ge)} tradeable items")

    items, seen = [], set()
    for title, text in texts.items():
        stats = parse_bonuses(text)
        combat_style = parse_combat_styles(text)
        for row in parse_item_page(title, text):
            if row["id"] in seen:
                continue
            seen.add(row["id"])
            if stats:
                row["equipmentStats"] = stats
            if combat_style:
                row["combatStyle"] = combat_style
            row["aliases"] = aliases.get(title, [])
            g = ge.get(row["id"])
            if g:
                row["buyLimit"] = g.get("limit") or row.get("buyLimit")
                row["highAlch"] = g.get("highalch")
                row["lowAlch"] = g.get("lowalch")
            items.append(row)
    items.sort(key=lambda r: r["id"])
    return items


# Items whose page carries several ids (coin stack graphics, for instance)
# would otherwise resolve to whichever sorts first. Pin the canonical one.
CANONICAL_IDS = {"coins": 995}


def item_lookup(items):
    """name -> id resolver, tolerant of the wiki's loose naming in recipes."""
    by_name = {}
    for it in items:
        # a record can have no name at all: infobox_item has rows for pages
        # like RuneScape:Templates, which carry an id of nothing and a name to
        # match. The wikitext builder never produced those, so this only
        # surfaced once a dataset built from Bucket was fed back in.
        if not it.get("name") or it.get("id") is None:
            continue
        by_name.setdefault(it["name"].lower(), it["id"])
        by_name.setdefault(it["page"].lower(), it["id"])
    by_name.update(CANONICAL_IDS)

    def lookup(name):
        if not name:
            return None
        n = name.lower().strip()
        # drop links carry section anchors, e.g. "Bird nest (egg)#Red egg"
        n = n.split("#", 1)[0].strip()
        # fall back to the name minus any trailing "(dose)"/"(variant)" suffix
        return by_name.get(n) or by_name.get(re.sub(r"\s*\(.*\)$", "", n))
    return lookup


# ----------------------------------------------------------------- recipes

MAT_KEYS = {"": "id", "quantity": "quantity", "cost": "cost",
            "itemnote": "notes", "txt": "text", "subtxt": "subText",
            "quantitynote": "quantityNote", "costnote": "costNote",
            "subtext": "subText", "qty": "quantity", "num": "quantity"}


def _materials(p, prefix, lookup):
    """mat1/mat1quantity/... -> [{id|name, quantity, ...}] in index order."""
    buckets = {}
    for key, val in p.items():
        m = re.fullmatch(re.escape(prefix) + r"(\d+)([a-z]*)", key)
        if not m:
            continue
        prop = m.group(2)
        if prop not in MAT_KEYS:
            continue
        buckets.setdefault(int(m.group(1)), {})[prop] = val

    mats = []
    for idx in sorted(buckets):
        b = buckets[idx]
        raw_name = strip_markup(b.get("", ""))
        if not raw_name:
            continue
        iid = lookup(raw_name)
        qty = b.get("quantity") or b.get("qty") or b.get("num")
        mat = {"quantity": wiki_int(qty, 1) or 1}
        # Unresolvable names are still real content that simply isn't an
        # inventory item (POH furniture, Sailing hull parts). Keep the name so
        # the recipe stays usable rather than dropping it.
        if iid is None:
            mat["name"] = raw_name
        else:
            mat["id"] = iid
        if "cost" in b:
            mat["cost"] = wiki_num(b["cost"])
        for prop in ("itemnote", "txt", "subtxt", "subtext",
                     "quantitynote", "costnote"):
            if b.get(prop):
                mat[MAT_KEYS[prop]] = strip_markup(b[prop])
        mats.append(mat)
    return mats


def _skills(p):
    """skill1/skill1lvl/... -> [{name, lvl, xp, boostable}]."""
    buckets = {}
    for key, val in p.items():
        # `skill`/`skilllvl` (unnumbered) is valid shorthand for skill1
        m = re.fullmatch(r"skill(\d*)([a-z]*)", key)
        if not m:
            continue
        buckets.setdefault(int(m.group(1) or 1), {})[m.group(2)] = val

    out = []
    for idx in sorted(buckets):
        b = buckets[idx]
        name = strip_markup(b.get("", ""))
        if not name:
            continue
        out.append({
            "name": wiki_title(name),
            "lvl": wiki_int(b.get("lvl"), 1) or 1,
            "xp": wiki_num(b.get("exp"), 0),
            "boostable": wiki_bool(b.get("boostable", "Yes")),
        })
    return out


def parse_recipe_page(title, text, lookup):
    out = []
    for block in find_templates(text, "Recipe"):
        p = template_params(block)
        outputs = _materials(p, "output", lookup)
        if not outputs:
            continue
        # the template parameter is `facilities` (plural)
        facility = p.get("facilities") or p.get("facility") or ""
        out.append({
            "name": strip_markup(p.get("name", "")) or None,
            "page": title,
            "skills": _skills(p),
            "members": wiki_bool(p.get("members")),
            # `ticks = 0` is meaningful (instant); "Varies" stays null
            "ticks": wiki_int(p.get("ticks")),
            "ticksNote": strip_markup(p.get("ticksnote", "")) or None,
            "notes": strip_markup(p.get("notes") or p.get("note") or "") or None,
            "facility": strip_markup(facility) or None,
            # `tools = Saw, Hammer` — comma-separated, not pipe-separated
            "toolIds": [i for i in (lookup(t) for t in
                                    wiki_list(p.get("tools") or p.get("tool")))
                        if i is not None],
            "inputs": _materials(p, "mat", lookup),
            "outputs": outputs,
        })
    return out


# ------------------------------------------------------------- ground spawns

def build_spawns(items):
    """Attach ground-spawn locations to item records, in place."""
    lookup = item_lookup(items)
    by_id = {it["id"]: it for it in items}
    titles = pages_embedding("Template:ItemSpawnLine")
    log(f"item spawn pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("spawns"))

    # `--only spawns` re-runs against an already-populated file, so clear first:
    # otherwise a row that moves to a different id leaves the old one stale
    for it in items:
        it.pop("spawns", None)

    attached = collections.defaultdict(list)
    rows = unresolved = 0
    for text in texts.values():
        for row in parse_item_spawns(text):
            rows += 1
            iid = lookup(row["name"])
            if iid is None or iid not in by_id:
                unresolved += 1
                log(f"  warn: spawn row for unknown item {row['name']!r}")
                continue
            attached[iid].append({k: v for k, v in row.items() if k != "name"})

    for iid, spawn_rows in attached.items():
        by_id[iid]["spawns"] = spawn_rows
    points = sum(len(s["spawns"]) for rs in attached.values() for s in rs)
    log(f"  {rows} spawn rows over {points} points on {len(attached)} items "
        f"({unresolved} unresolved)")
    return items


def _recipe_identity(recipe):
    """What makes two recipes the same production step."""
    return json.dumps({
        "inputs": sorted(json.dumps(m, sort_keys=True) for m in recipe["inputs"]),
        "outputs": sorted(json.dumps(m, sort_keys=True) for m in recipe["outputs"]),
        "skills": sorted(json.dumps(s, sort_keys=True) for s in recipe["skills"]),
        "ticks": recipe["ticks"],
        "facility": recipe["facility"],
        "members": recipe["members"],
        "toolIds": sorted(recipe["toolIds"]),
    }, sort_keys=True)


def build_recipes(items):
    lookup = item_lookup(items)
    titles = pages_embedding("Template:Recipe")
    log(f"recipe pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("recipes"))

    # The same production step is often stated on several pages (and sometimes
    # twice on one). Keep the first; `page` records where it came from.
    recipes, seen = [], set()
    dropped = 0
    for title, text in texts.items():
        for recipe in parse_recipe_page(title, text, lookup):
            key = _recipe_identity(recipe)
            if key in seen:
                dropped += 1
                continue
            seen.add(key)
            recipes.append(recipe)
    log(f"  dropped {dropped} duplicate recipes")
    return recipes


# -------------------------------------------------------------------- cli

COMPACT_FIELDS = ("aliases", "equipmentStats", "options", "combatStyle",
                  "wornOptions")


def write_shape(records, flat=False):
    """Default output hoists page-identical fields into a `pages` side table.

    Records that share a wiki page repeat the same drops, locations and aliases
    verbatim; storing them once cuts the dataset by a third overall. Lossless --
    a field only moves when every record on the page agrees, and
    osrs_wiki.load_dataset reads either shape. `--flat` restores the plain array
    for hand-inspection.
    """
    if flat:
        return records
    recs, pages = compact_by_page(records, COMPACT_FIELDS)
    return {"pages": pages, "records": recs}


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    ap.add_argument("--flat", action="store_true",
                    help="write a plain array instead of the compact "
                         "`pages`/`records` shape")
    ap.add_argument("--only", choices=["items", "recipes", "spawns"],
                    help="build one phase only; recipes and spawns reuse an "
                         "existing all-items.json")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    items_path = out / "all-items.json"

    if args.only in ("recipes", "spawns"):
        if not items_path.exists():
            sys.exit(f"{items_path} not found — run without --only first")
        items = json.loads(items_path.read_text())
        log(f"loaded {len(items)} items")
    else:
        t0 = time.time()
        items = build_items()
        log(f"built {len(items)} items ({time.time() - t0:.0f}s)")

    # ground spawns live on item records, so they are written with the items
    if args.only in (None, "items", "spawns"):
        t0 = time.time()
        build_spawns(items)
        items_path.write_text(json.dumps(write_shape(items, args.flat), indent=1))
        log(f"wrote {items_path}  ({len(items)} items, {time.time() - t0:.0f}s)")
        if args.only in ("items", "spawns"):
            return

    t0 = time.time()
    recipes = build_recipes(items)
    path = out / "all-recipes.json"
    path.write_text(json.dumps(recipes, indent=1))
    log(f"wrote {path}  ({len(recipes)} recipes, {time.time() - t0:.0f}s)")


if __name__ == "__main__":
    main()
