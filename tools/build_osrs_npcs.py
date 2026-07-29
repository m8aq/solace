#!/usr/bin/env python3
"""
Build all-npcs.json from the live OSRS wiki.

Reads {{Infobox NPC}} (~4,450 pages) for identity, location, actions and spawn
coordinates. Monsters live in a separate template and are handled by
build_osrs_monsters.py.

Spawn coordinates come from {{Map}} blocks inside the infobox's `map` param.
A Map block carries points two ways -- a named pair:

    {{Map|name=X|x=2224|y=3803|plane=1|r=5|mtype=square}}

and/or positional pairs, one per point:

    {{Map|mtype=dot|1843,3728|1847,3728|1851,3728}}

Both are collected, and each point keeps the plane and mapID of its block, so
upper floors and dungeon instances resolve to the right tile.

Usage:
    python3 tools/build_osrs_npcs.py --out data/wiki
    python3 tools/build_osrs_npcs.py --out data/wiki --no-aliases
"""

import argparse
import json
import re
import time
from pathlib import Path

from build_osrs_items import item_lookup
from osrs_wiki import (
    wiki_title,
    parse_ids,
    compact_by_page, fetch_redirects, fetch_wikitext, find_templates,
    load_dataset, log, pages_embedding, parse_drops, parse_loc_lines,
    parse_map_features, progress_bar, strip_markup, template_params, versioned,
    wiki_bool, wiki_file, wiki_int, wiki_list,
)


def parse_npc_page(title, text, lookup=None):
    """{{Infobox NPC}} -> [{id, name, ...}], one record per variant id."""
    rows = []
    # named places live in the page body's Locations table, not the infobox
    locations = parse_loc_lines(text)
    # NPC pages carry pickpocketing loot via {{DropsLineSkill}}
    drops = parse_drops(text, lookup)
    for block in find_templates(text, "Infobox NPC"):
        p = template_params(block)
        idxs = sorted({m.group(1) for k in p
                       for m in [re.fullmatch(r"id(\d*)", k)] if m})
        for idx in idxs:
            v = versioned(p, idx)
            # a variant's own map, else the shared one
            spawns = parse_map_features(v("map", ""))
            for one in parse_ids(p.get(f"id{idx}")):
                rows.append({
                    "id": one,
                    "name": strip_markup(v("name") or "") or title,
                    "page": title,
                    "version": strip_markup(v("version", "")) or None,
                    "examine": strip_markup(v("examine", "")) or None,
                    "isMembers": wiki_bool(v("members")),
                    "race": wiki_title(strip_markup(v("race", ""))),
                    "gender": strip_markup(v("gender", "")) or None,
                    "location": strip_markup(v("location", "")) or None,
                    "quest": strip_markup(v("quest", "")) or None,
                    "level": wiki_int(v("level")),
                    "size": wiki_int(v("size")),
                    "respawnTime": wiki_int(v("respawn")),
                    "shop": strip_markup(v("shop", "")) or None,
                    "actions": wiki_list(v("options")),
                    "image": wiki_file(v("image")),
                    "release": strip_markup(v("release", "")) or None,
                    "releaseUpdate": strip_markup(v("update", "")) or None,
                    "removal": strip_markup(v("removal", "")) or None,
                    "removalUpdate": strip_markup(v("removalupdate", "")) or None,
                    "leagueRegion": wiki_title(strip_markup(v("leagueregion", ""))),
                    "aka": wiki_list(v("aka")),
                    "spawns": spawns,
                    "locations": locations,
                    "drops": drops,
                })
    return rows


def build_npcs(lookup=None, with_aliases=True):
    titles = pages_embedding("Template:Infobox NPC")
    log(f"NPC pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("npcs"))

    aliases = {}
    if with_aliases:
        aliases = fetch_redirects(titles, progress=progress_bar("aliases"))

    npcs, seen = [], set()
    for title, text in texts.items():
        for row in parse_npc_page(title, text, lookup):
            if row["id"] in seen:
                continue
            seen.add(row["id"])
            row["aliases"] = aliases.get(title, [])
            npcs.append(row)
    npcs.sort(key=lambda r: r["id"])
    return npcs


COMPACT_FIELDS = ("locations", "aliases", "drops", "spawns", "actions",
                  "shopNames")


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
    ap.add_argument("--items", help="all-items.json path, for resolving drop "
                                    "names (default: <out>/all-items.json)")
    ap.add_argument("--flat", action="store_true",
                    help="write a plain array instead of the compact "
                         "`pages`/`records` shape")
    ap.add_argument("--no-aliases", action="store_true",
                    help="skip the redirect fetch (roughly halves runtime)")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    lookup = None
    items_path = Path(args.items) if args.items else out / "all-items.json"
    if items_path.exists():
        items = load_dataset(items_path)
        lookup = item_lookup(items)
        log(f"loaded {len(items)} items for drop lookup")
    else:
        log(f"note: {items_path} not found; pickpocket drops keep names only")

    t0 = time.time()
    npcs = build_npcs(lookup, with_aliases=not args.no_aliases)
    path = out / "all-npcs.json"
    path.write_text(json.dumps(write_shape(npcs, args.flat), indent=1))

    mapped = sum(1 for n in npcs if n["spawns"])
    points = sum(len(n["spawns"]) for n in npcs)
    located = sum(len(L["spawns"]) for n in npcs for L in n["locations"])
    drops = sum(len(n["drops"]) for n in npcs)
    log(f"wrote {path}  ({len(npcs)} npcs, {time.time() - t0:.0f}s)")
    log(f"  {mapped} with infobox spawns, {points} points; "
        f"{located} located points; {drops} drop entries")


if __name__ == "__main__":
    main()
