#!/usr/bin/env python3
"""
Build the all-*.json datasets from Bucket rather than from wikitext.

The wikitext builders name every template they read and every parameter on it,
then re-derive item ids by matching names. That is where the bugs were:
`facilities` read as `facility`, `{{NPC map}}` missed for being an alias, ids
invented from `hist1`, welded values from a dropped `<br>`.

Bucket is the wiki's own structured store -- 47 tables holding what those
templates wrote, queryable with joins server-side. Reading from it removes the
template names, the parameter spellings and the id matching in one go, and it
is fast: a dataset is a handful of paginated queries rather than thousands of
page fetches.

What it does not remove is the merging. Bucket has no groupBy, so collapsing
records across page variants stays here, as does the RuneLite cache join in
scenery, which no wiki table can hold.

One mapping per dataset rather than one script per dataset: the datasets
differ only in which table they read and what the fields are called, and eight
copies of the same fetch-and-rename loop is how the spellings drifted in the
first place.

Usage:
    python3 tools/build_osrs_bucket.py --list
    python3 tools/build_osrs_bucket.py monsters --out /tmp/monsters.json
    python3 tools/build_osrs_bucket.py --all --compare data/wiki
"""

import argparse
import json
from pathlib import Path

from osrs_wiki import api_get, load_dataset, log

PAGE = 2500


def bucket(table, fields, joins=(), where=None):
    """One Bucket query, paginated to exhaustion."""
    select = ",".join(f'"{f}"' for f in fields)
    clauses = "".join(f'.join("{t}","{a}","{b}")' for t, a, b in joins)
    if where:
        clauses += f'.where("{where[0]}","{where[1]}")'
    rows, offset = [], 0
    while True:
        query = (f'bucket("{table}"){clauses}.select({select})'
                 f".limit({PAGE}).offset({offset}).run()")
        got = api_get({"action": "bucket", "query": query}).get("bucket") or []
        rows += got
        if len(got) < PAGE:
            return rows
        offset += PAGE


def one(value):
    """Bucket returns repeated fields as lists; take the single value."""
    if isinstance(value, list):
        return value[0] if value else None
    return value


def ids(value):
    """A field that holds several ids -> a sorted list of ints."""
    values = value if isinstance(value, list) else [value]
    out = set()
    for v in values:
        try:
            out.add(int(str(v).strip()))
        except (TypeError, ValueError):
            continue
    return sorted(out)


def num(value):
    try:
        return int(str(one(value)).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


def flag(value):
    return str(one(value)).strip().lower() in ("1", "true", "yes")


def text(value):
    got = one(value)
    got = str(got).strip() if got is not None else ""
    return got or None


# Each dataset: the table it comes from, and what its fields are called here.
# `page_name` is always available and is the join key between tables.
DATASETS = {
    "items": {
        "table": "infobox_item",
        "fields": ["page_name", "item_name", "item_id", "value", "weight",
                   "examine", "high_alchemy_value", "buy_limit", "tradeable",
                   "is_members_only", "release_date", "quest", "league_region"],
        "map": {
            "name": ("item_name", text), "page": ("page_name", text),
            "id": ("item_id", num), "ids": ("item_id", ids),
            "value": ("value", num), "weight": ("weight", one),
            "examine": ("examine", text),
            "highAlch": ("high_alchemy_value", num),
            "buyLimit": ("buy_limit", num),
            "isTradeable": ("tradeable", flag),
            "isMembers": ("is_members_only", flag),
            "release": ("release_date", text), "quest": ("quest", text),
            "leagueRegion": ("league_region", text),
        },
        "against": "all-items.json",
    },
    "monsters": {
        "table": "infobox_monster",
        "fields": ["page_name", "name", "id", "combat_level", "hitpoints",
                   "max_hit", "attack_level", "strength_level",
                   "defence_level", "ranged_level", "magic_level",
                   "attack_style", "attack_speed", "size", "poisonous",
                   "slayer_level", "slayer_experience", "slayer_category",
                   "examine", "is_members_only", "attribute", "flat_armour"],
        "map": {
            "name": ("name", text), "page": ("page_name", text),
            "id": ("id", num), "ids": ("id", ids),
            "combatLevel": ("combat_level", num),
            "hitpoints": ("hitpoints", num), "maxHit": ("max_hit", one),
            "attackLevel": ("attack_level", num),
            "strengthLevel": ("strength_level", num),
            "defenceLevel": ("defence_level", num),
            "rangedLevel": ("ranged_level", num),
            "magicLevel": ("magic_level", num),
            "attackStyle": ("attack_style", one),
            "attackSpeed": ("attack_speed", num),
            "size": ("size", num), "poisonous": ("poisonous", one),
            "slayerLevel": ("slayer_level", num),
            "slayerXp": ("slayer_experience", one),
            "slayerCategory": ("slayer_category", text),
            "examine": ("examine", text),
            "isMembers": ("is_members_only", flag),
            "attributes": ("attribute", lambda v: v if isinstance(v, list) else
                           ([v] if v else [])),
            "flatArmour": ("flat_armour", num),
        },
        "against": "all-monsters.json",
    },
    "npcs": {
        "table": "infobox_npc",
        "fields": ["page_name", "npc_name", "npc_id", "examine", "location",
                   "is_members_only", "release", "quest", "league_region"],
        "map": {
            "name": ("npc_name", text), "page": ("page_name", text),
            "id": ("npc_id", num), "ids": ("npc_id", ids),
            "examine": ("examine", text), "location": ("location", text),
            "isMembers": ("is_members_only", flag),
            "release": ("release", text), "quest": ("quest", text),
            "leagueRegion": ("league_region", text),
        },
        "against": "all-npcs.json",
    },
    "scenery": {
        "table": "infobox_scenery",
        "fields": ["page_name", "object_id", "npc_id", "is_members_only",
                   "release", "league_region"],
        "map": {
            "page": ("page_name", text), "name": ("page_name", text),
            "id": ("object_id", num), "objectIds": ("object_id", ids),
            "npcIds": ("npc_id", ids),
            "isMembers": ("is_members_only", flag),
            "release": ("release", text),
            "leagueRegion": ("league_region", text),
        },
        "against": "all-scenery.json",
    },
    "construction": {
        "table": "infobox_construction",
        "fields": ["page_name", "object_id", "item_id", "level", "experience",
                   "uses_skill"],
        "map": {
            "page": ("page_name", text), "name": ("page_name", text),
            "objectIds": ("object_id", ids), "iconItemIds": ("item_id", ids),
            "level": ("level", num), "experience": ("experience", one),
            "usesSkill": ("uses_skill", one),
        },
        "against": "all-construction.json",
    },
    "spells": {
        "table": "infobox_spell",
        "fields": ["page_name", "spellbook", "uses_material",
                   "is_members_only"],
        "map": {
            "name": ("page_name", text), "page": ("page_name", text),
            "spellbook": ("spellbook", text),
            "runes": ("uses_material", lambda v: v if isinstance(v, list)
                      else ([v] if v else [])),
            "isMembers": ("is_members_only", flag),
        },
        "against": "all-spells.json",
    },
    "quests": {
        "table": "quest",
        "fields": ["page_name", "description", "official_difficulty",
                   "official_length", "start_point", "items_required",
                   "requirements", "enemies_to_defeat", "ironman_concerns"],
        "map": {
            "name": ("page_name", text), "page": ("page_name", text),
            "description": ("description", text),
            "difficulty": ("official_difficulty", text),
            "length": ("official_length", text),
            "startPoint": ("start_point", text),
            "itemsRequired": ("items_required", one),
            "requirements": ("requirements", one),
            "enemies": ("enemies_to_defeat", one),
            "ironmanConcerns": ("ironman_concerns", one),
        },
        "against": "all-quests.json",
    },
    "recipes": {
        "table": "recipe",
        "fields": ["page_name", "uses_material", "uses_tool", "uses_facility",
                   "uses_skill", "is_members_only", "is_boostable",
                   "production_json"],
        "map": {
            "page": ("page_name", text),
            "materials": ("uses_material", lambda v: v if isinstance(v, list)
                          else ([v] if v else [])),
            "tools": ("uses_tool", lambda v: v if isinstance(v, list)
                      else ([v] if v else [])),
            "facility": ("uses_facility", text),
            "skills": ("uses_skill", lambda v: v if isinstance(v, list)
                       else ([v] if v else [])),
            "isMembers": ("is_members_only", flag),
            "isBoostable": ("is_boostable", flag),
        },
        "against": "all-recipes.json",
    },
}


def expand_ids(records, id_key, list_key):
    """One record per id.

    Bucket returns a page's ids as a list -- a scenery page covers every
    object that shares its name -- but the cache is keyed by a single id, so
    the join needs one record each.
    """
    out = []
    for record in records:
        for ident in record.get(list_key) or []:
            out.append({**record, id_key: ident})
    return out


def build(name, cache_dump=None):
    spec = DATASETS[name]
    rows = bucket(spec["table"], spec["fields"])
    out = []
    for row in rows:
        record = {}
        for key, (field, coerce) in spec["map"].items():
            record[key] = coerce(row.get(field))
        out.append(record)

    if name == "scenery" and cache_dump:
        # The cache is the other half of scenery: sizes, collision masks,
        # varbit transforms and the authoritative right-click list, none of
        # which the wiki records. Reuses the join already written and tested
        # for the wikitext builder rather than a second copy of it.
        from build_osrs_scenery import join_cache, load_cache_objects
        cache = load_cache_objects(cache_dump)
        if cache:
            from build_osrs_scenery import drop_inert
            out = join_cache(expand_ids(out, "objectId", "objectIds"), cache)
            out, inert = drop_inert(out)
            log(f"  joined {len(cache)} cache objects, "
                f"dropped {len(inert)} with no actions")
        else:
            log(f"  no cache dump at {cache_dump}; wiki fields only")

    out.sort(key=lambda r: (r.get("page") or "", str(r.get("id") or "")))
    return out


# Both sides spell the id differently per dataset, and a record can carry
# several: an item with three versions has three ids. Comparing only `id`
# reported 999 items "lost" while Bucket held more records than we did.
ID_KEYS = ("id", "ids", "objectId", "objectIds", "npcIds", "iconItemIds")


def all_ids(records):
    out = set()
    for record in records:
        for key in ID_KEYS:
            value = record.get(key)
            if value is None:
                continue
            out.update(value if isinstance(value, list) else [value])
    return {i for i in out if isinstance(i, int)}


def compare(name, built, directory):
    """Counts, id coverage and page coverage against the wikitext dataset."""
    path = Path(directory) / DATASETS[name]["against"]
    if not path.exists():
        log(f"  {name:<13} no {path.name} to compare against")
        return
    old = load_dataset(path)
    old_ids, new_ids = all_ids(old), all_ids(built)
    old_pages = {r.get("page") for r in old if r.get("page")}
    new_pages = {r.get("page") for r in built if r.get("page")}
    shared = f"{len(old_ids & new_ids):,}" if old_ids else "-"
    log(f"  {name:<13}{len(old):>8,}{len(built):>9,}"
        f"{shared:>10}{len(new_ids - old_ids):>9,}"
        f"{len(old_ids - new_ids):>9,}"
        f"{100 * len(old_pages & new_pages) // max(len(old_pages), 1):>7}%")


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("dataset", nargs="?", choices=sorted(DATASETS))
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--out")
    ap.add_argument("--cache-dump", default=str(Path.home() /
                    ".solace" / "cache-dump" / "objects"),
                    help="RuneLite object dump to fold into scenery")
    ap.add_argument("--compare", metavar="DIR",
                    help="directory of existing all-*.json to diff against")
    args = ap.parse_args()

    if args.list:
        for name, spec in sorted(DATASETS.items()):
            log(f"  {name:<14} bucket({spec['table']})  "
                f"{len(spec['map'])} fields -> {spec['against']}")
        return

    names = sorted(DATASETS) if args.all else [args.dataset]
    if not names or names == [None]:
        raise SystemExit("give a dataset, or --all, or --list")

    if args.compare:
        log(f"\n  {'dataset':<13}{'wikitext':>8}{'bucket':>9}"
            f"{'shared':>10}{'new':>9}{'lost':>9}{'pages':>8}")
    for name in names:
        built = build(name, args.cache_dump)
        if args.out:
            path = Path(args.out) if len(names) == 1 else \
                Path(args.out) / f"all-{name}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(built, indent=1))
        if args.compare:
            compare(name, built, args.compare)
        elif not args.out:
            log(f"  {name:<13} {len(built)} records")


if __name__ == "__main__":
    main()
