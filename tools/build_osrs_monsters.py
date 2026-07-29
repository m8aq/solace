#!/usr/bin/env python3
"""
Build all-monsters.json from the live OSRS wiki.

Reads {{Infobox Monster}} (~1,600 pages) for combat stats and {{DropsLine}} /
{{DropsLineClue}} from the page body for drop tables. Non-combat NPCs live in a
separate template and are handled by build_osrs_npcs.py.

Monsters carry no infobox `map` param -- their coordinates come from
{{LocLine}} in the page body, as `locations`.

A monster page shares one stat block across its variants (version1..N), each
with its own id, and an id param may itself list several ids:

    |id1 = 2098,13502
    |id2 = 2099,13503

so one page can yield many records, all carrying the same combat stats.

Drop names are resolved to item ids against all-items.json; build that first
with build_osrs_items.py. Names that don't resolve are kept as names rather
than dropped.

Usage:
    python3 tools/build_osrs_monsters.py --out data/wiki
    python3 tools/build_osrs_monsters.py --out data/wiki --items data/wiki/all-items.json
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

from build_osrs_items import item_lookup
from osrs_wiki import (
    wiki_title,
    parse_ids,
    compact_by_page, fetch_redirects, fetch_wikitext, find_templates,
    load_dataset, log, pages_embedding, parse_drop_tables, parse_drops,
    parse_loc_lines, progress_bar, strip_markup,
    template_params, versioned, wiki_bool, wiki_file, wiki_int, wiki_list,
)

# wiki param -> output field. Values are ints; absent params stay absent.
COMBAT_KEYS = {
    "combat": "combatLevel", "hitpoints": "hitpoints", "size": "size",
    "att": "attackLevel", "str": "strengthLevel", "def": "defenceLevel",
    "mage": "magicLevel", "range": "rangedLevel",
    "attbns": "attackBonus", "strbns": "strengthBonus",
    "amagic": "magicAttackBonus", "mbns": "magicDamageBonus",
    "arange": "rangedAttackBonus", "rngbns": "rangedStrengthBonus",
    "dstab": "defenceStab", "dslash": "defenceSlash", "dcrush": "defenceCrush",
    "dmagic": "defenceMagic", "dlight": "defenceLightRanged",
    "dstandard": "defenceStandardRanged", "dheavy": "defenceHeavyRanged",
    "flatarmour": "flatArmour", "xpbonus": "xpBonus",
    "attack speed": "attackSpeed", "respawn": "respawnTime",
    "slaylvl": "slayerLevel", "slayxp": "slayerXp",
    "poisonresistance": "poisonResistance",
    "venomresistance": "venomResistance",
    "freezeresistance": "freezeResistance",
    "elementalweaknesspercent": "elementalWeaknessPercent",
}

IMMUNITY_KEYS = {
    "immunepoison": "immuneToPoison", "immunevenom": "immuneToVenom",
    "immunecannon": "immuneToCannon", "immunethrall": "immuneToThrall",
    "immuneburn": "immuneToBurn",
}


def parse_monster_page(title, text, lookup):
    """{{Infobox Monster}} -> [{id, name, combat stats, drops, ...}]."""
    rows = []
    drops = parse_drops(text, lookup)
    # shared tables (herb/gem/rare) are referenced, not expanded, on the page
    drop_tables = parse_drop_tables(text)
    # named places (dungeons, guild basements) live in the body's Locations
    # table; the infobox `map` param only covers a headline location
    locations = parse_loc_lines(text)
    for block in find_templates(text, "Infobox Monster"):
        p = template_params(block)
        idxs = sorted({m.group(1) for k in p
                       for m in [re.fullmatch(r"id(\d*)", k)] if m})
        for idx in idxs:
            v = versioned(p, idx)
            row = {
                "id": None,
                "name": strip_markup(v("name") or "") or title,
                "page": title,
                "version": strip_markup(v("version", "")) or None,
                "examine": strip_markup(v("examine", "")) or None,
                "isMembers": wiki_bool(v("members")),
                "isAggressive": wiki_bool(v("aggressive")),
                "poisonous": wiki_title(strip_markup(v("poisonous", ""))),
                "attackStyle": wiki_title(strip_markup(v("attack style", ""))),
                "maxHit": strip_markup(v("max hit", "")) or None,
                "attributes": wiki_list(v("attributes"), title=True),
                "slayerCategory": strip_markup(v("cat", "")) or None,
                "assignedBy": wiki_list(v("assignedby"), title=True),
                "elementalWeaknessType": wiki_title(strip_markup(
                    v("elementalweaknesstype", ""))),
                "image": wiki_file(v("image")),
                "release": strip_markup(v("release", "")) or None,
                "releaseUpdate": strip_markup(v("update", "")) or None,
                "removal": strip_markup(v("removal", "")) or None,
                "removalUpdate": strip_markup(v("removalupdate", "")) or None,
                "leagueRegion": wiki_title(strip_markup(v("leagueregion", ""))),
                "aka": wiki_list(v("aka")),
                # hitpoints in scaled encounters (e.g. group bosses)
                "scaledHitpoints": strip_markup(v("scaledhp", "")) or None,
                "locations": locations,
                "drops": drops,
                "dropTables": drop_tables,
            }
            for wk, out_key in COMBAT_KEYS.items():
                val = wiki_int(v(wk))
                if val is not None:
                    row[out_key] = val
            for wk, out_key in IMMUNITY_KEYS.items():
                raw = v(wk)
                if raw is not None:
                    row[out_key] = wiki_bool(raw)

            # an id param may list several ids for the same variant
            for one in parse_ids(p.get(f"id{idx}")):
                rows.append({**row, "id": one})
    return rows


def build_monsters(lookup, with_aliases=True):
    titles = pages_embedding("Template:Infobox Monster")
    log(f"monster pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("monsters"))

    aliases = {}
    if with_aliases:
        aliases = fetch_redirects(titles, progress=progress_bar("aliases"))

    monsters, seen = [], set()
    for title, text in texts.items():
        for row in parse_monster_page(title, text, lookup):
            if row["id"] in seen:
                continue
            seen.add(row["id"])
            row["aliases"] = aliases.get(title, [])
            monsters.append(row)
    monsters.sort(key=lambda r: r["id"])
    return monsters


COMPACT_FIELDS = ("drops", "locations", "dropTables", "aliases",
                  "assignedBy", "attributes")


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
    ap.add_argument("--items", help="all-items.json path (default: <out>/all-items.json)")
    ap.add_argument("--flat", action="store_true",
                    help="write a plain array instead of the compact "
                         "`pages`/`records` shape")
    ap.add_argument("--no-aliases", action="store_true",
                    help="skip the redirect fetch (roughly halves runtime)")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    items_path = Path(args.items) if args.items else out / "all-items.json"
    if not items_path.exists():
        sys.exit(f"{items_path} not found — run build_osrs_items.py first "
                 f"(drop names are resolved against it)")
    items = load_dataset(items_path)
    log(f"loaded {len(items)} items for drop lookup")

    t0 = time.time()
    monsters = build_monsters(item_lookup(items),
                              with_aliases=not args.no_aliases)
    path = out / "all-monsters.json"
    path.write_text(json.dumps(write_shape(monsters, args.flat), indent=1))

    with_drops = sum(1 for m in monsters if m["drops"])
    total_drops = sum(len(m["drops"]) for m in monsters)
    unresolved = sum(1 for m in monsters for d in m["drops"] if "id" not in d)
    log(f"wrote {path}  ({len(monsters)} monsters, {time.time() - t0:.0f}s)")
    log(f"  {with_drops} with drops, {total_drops} drop entries "
        f"({unresolved} unresolved names)")


if __name__ == "__main__":
    main()
