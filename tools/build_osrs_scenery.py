#!/usr/bin/env python3
"""
Build all-scenery.json from the live OSRS wiki.

Reads {{Infobox Scenery}} (~5,900 pages) -- the world objects players interact
with: trees, rocks, fishing spots, farming patches, doors, altars. This is the
missing half of the acquisition graph: recipes say what you combine, but only
scenery says where raw materials come from.

Three things come off these pages:

  produce   {{DropsLineSkill}}  Oak tree -> Oak logs (Woodcutting)
  skillInfo {{Woodcutting info}} and friends: level, xp, tool, timing
  locations {{ObjectLocLine}}   where the object stands, with plane and mapID

Farming is the case that motivated this: {{Farming info}} carries seed -> crop,
so `Snape grass seed` finally connects to `Snape grass`.

Usage:
    python3 tools/build_osrs_scenery.py --out data/wiki
    python3 tools/build_osrs_scenery.py --out data/wiki --flat
"""

import argparse
import collections
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path

from build_osrs_items import item_lookup
from osrs_wiki import (
    wiki_title,
    parse_ids,
    compact_by_page, fetch_wikitext, find_templates, load_dataset, log,
    pages_embedding, parse_drops, parse_loc_lines, parse_map_features,
    parse_skill_info, progress_bar, strip_markup, template_params, versioned,
    wiki_bool, wiki_file, wiki_list,
)


def parse_scenery_page(title, text, lookup=None):
    """{{Infobox Scenery}} -> [{objectId, name, produce, skillInfo, ...}]."""
    # all three of these describe the page, not one variant
    produce = parse_drops(text, lookup)
    skill_info = parse_skill_info(text)
    locations = parse_loc_lines(text, "ObjectLocLine")

    rows = []
    for block in find_templates(text, "Infobox Scenery"):
        p = template_params(block)
        idxs = sorted({m.group(1) for k in p
                       for m in [re.fullmatch(r"id(\d*)", k)] if m})
        for idx in idxs:
            v = versioned(p, idx)
            spawns = parse_map_features(v("map", ""))
            for one in parse_ids(p.get(f"id{idx}")):
                rows.append({
                    "objectId": one,
                    "name": strip_markup(v("name") or "") or title,
                    "page": title,
                    "version": strip_markup(v("version", "")) or None,
                    "examine": strip_markup(v("examine", "")) or None,
                    "isMembers": wiki_bool(v("members")),
                    "actions": wiki_list(v("options")),
                    "location": strip_markup(v("location", "")) or None,
                    "quest": strip_markup(v("quest", "")) or None,
                    "leagueRegion": wiki_title(strip_markup(v("leagueregion", ""))),
                    "image": wiki_file(v("image")),
                    "release": strip_markup(v("release", "")) or None,
                    "releaseUpdate": strip_markup(v("update", "")) or None,
                    "spawns": spawns,
                    "locations": locations,
                    "produce": produce,
                    "skillInfo": skill_info,
                })
    return rows


def build_scenery(lookup=None):
    titles = pages_embedding("Template:Infobox Scenery")
    log(f"scenery pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("scenery"))

    rows, seen = [], set()
    for title, text in texts.items():
        for row in parse_scenery_page(title, text, lookup):
            if row["objectId"] in seen:
                continue
            seen.add(row["objectId"])
            rows.append(row)
    rows.sort(key=lambda r: r["objectId"])
    return rows


# Cache fields worth carrying. The wiki has none of these: sizes and collision
# drive pathfinding, and varbit/varp/configChangeDest are how one conceptual
# object ("Bank booth") becomes many ids that swap with game state.
CACHE_FIELDS = {
    "category": "category",
    "sizeX": "sizeX",
    "sizeY": "sizeY",
    "interactType": "interactType",
    "blockingMask": "blockingMask",
    "wallOrDoor": "wallOrDoor",
    "supportsItems": "supportsItems",
    "varbitID": "varbitId",
    "varpID": "varpId",
    "animationID": "animationId",
    "mapSceneID": "mapSceneId",
}


def _cache_actions(obj):
    """The `ops` block -> the right-click list, in slot order."""
    raw = (obj.get("ops") or {}).get("ops") or []
    return [o["text"] for o in raw
            if isinstance(o, dict) and o.get("text")
            and o["text"].lower() != "null"]


def cache_dump_age(path):
    """(iso timestamp, days old) for the dump, or None if it is absent.

    Cache data goes stale on every weekly game update while wiki data does not,
    so the two halves of this file drift on different clocks. Recording when
    the dump was taken makes that visible instead of silent.
    """
    directory = Path(path)
    if not directory.is_dir():
        return None
    newest = max((f.stat().st_mtime for f in directory.glob("*.json")),
                 default=None)
    if newest is None:
        return None
    stamp = datetime.fromtimestamp(newest, tz=timezone.utc)
    age = (datetime.now(tz=timezone.utc) - stamp).days
    return stamp.isoformat(timespec="seconds"), age


def load_cache_objects(path):
    """{objectId: {cache fields}} from a `dumpObjects` run.

    Keeps objects that are named or interactable. The dump holds 62k
    definitions but roughly half are unnamed, action-less decoration -- shrubs
    and wall segments that no consumer can act on.
    """
    directory = Path(path)
    if not directory.is_dir():
        return {}
    out = {}
    for file in directory.glob("*.json"):
        try:
            obj = json.loads(file.read_text())
        except (json.JSONDecodeError, OSError):
            continue
        name = (obj.get("name") or "").strip()
        if name.lower() == "null":
            name = ""
        actions = _cache_actions(obj)
        if not name and not actions:
            continue
        row = {"name": name or None, "actions": actions}
        for src, dst in CACHE_FIELDS.items():
            val = obj.get(src)
            # the cache uses -1 for "unset" on its id-valued fields
            if val is None or (dst.endswith("Id") and val == -1):
                continue
            row[dst] = val
        transforms = obj.get("configChangeDest")
        if transforms:
            row["configChangeDest"] = [t for t in transforms if t != -1]
        out[obj["id"]] = row
    return out


def drop_inert(scenery, strict=False):
    """Remove objects nothing can interact with.

    An object with no right-click is scenery a consumer cannot act on: walls,
    fences, decorative statues. The cache-only ones were already filtered when
    joining, but a wiki page can document an object that has no actions
    either, and those are 22% of the file.

    Kept regardless: anything carrying `produce` or `skillInfo`. Soil yields
    charcoal through Mining and a chest yields Khazard armour, both with no
    action on the record -- dropping them would lose gathering data rather
    than decoration. `strict` drops those too.

    Returns (kept, dropped).
    """
    kept, dropped = [], []
    for record in scenery:
        actionable = bool(record.get("actions") or record.get("wikiActions"))
        gathers = bool(record.get("produce") or record.get("skillInfo"))
        (kept if actionable or (gathers and not strict) else dropped).append(
            record)
    return kept, dropped


def join_cache(scenery, cache):
    """Fold cache definitions into the wiki records, keeping both sides.

    The wiki wins for meaning (examine, produce, skillInfo, locations); the
    cache wins for identity and physics (actions, sizes, collision, varbits).
    Cache-only objects are emitted too -- that is most of the coverage gain.
    """
    by_id = {s["objectId"]: s for s in scenery}
    for obj_id, row in cache.items():
        rec = by_id.get(obj_id)
        if rec is None:
            # A cache-only object with no actions is scenery nothing can touch
            # -- walls, water, decorative gates. Named, real, and useless to a
            # consumer, so it is not emitted. An action-less object that *does*
            # have a wiki page still gets its cache fields below.
            if not row["actions"]:
                continue
            by_id[obj_id] = {"objectId": obj_id, "name": row.get("name"),
                             "page": None, "source": "cache",
                             "spawns": [], "locations": [], "produce": [],
                             "skillInfo": [],
                             **{k: v for k, v in row.items() if k != "name"}}
            continue
        rec["source"] = "both"
        # the cache's op list is authoritative; keep the wiki's if it differs
        if row["actions"] and row["actions"] != rec.get("actions"):
            if rec.get("actions"):
                rec["wikiActions"] = rec["actions"]
            rec["actions"] = row["actions"]
        for key, val in row.items():
            if key in ("name", "actions"):
                continue
            rec.setdefault(key, val)
    for rec in scenery:
        rec.setdefault("source", "wiki")
    return sorted(by_id.values(), key=lambda r: r["objectId"])


# OSRS ships weekly, so a dump older than this has probably drifted.
CACHE_STALE_DAYS = 7

COMPACT_FIELDS = ("produce", "skillInfo", "locations", "actions")


def write_shape(records, flat=False, provenance=None):
    """Page-identical fields move into a `pages` side table (see osrs_wiki)."""
    if flat:
        return records
    recs, pages = compact_by_page(records, COMPACT_FIELDS)
    out = {"pages": pages, "records": recs}
    if provenance:
        out["meta"] = provenance
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    ap.add_argument("--items", help="all-items.json path "
                                    "(default: <out>/all-items.json)")
    ap.add_argument("--flat", action="store_true",
                    help="write a plain array instead of the compact "
                         "`pages`/`records` shape")
    ap.add_argument("--cache-dump",
                    default=str(Path.home() / ".solace/cache-dump/objects"),
                    help="object dump from `./gradlew :cache-tools:dumpObjects`")
    ap.add_argument("--no-cache", action="store_true",
                    help="wiki only; skip the game-cache join")
    ap.add_argument("--strict-actions", action="store_true",
                    help="also drop action-less objects that carry produce "
                         "or skillInfo")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    lookup = None
    items_path = Path(args.items) if args.items else out / "all-items.json"
    if items_path.exists():
        items = load_dataset(items_path)
        lookup = item_lookup(items)
        log(f"loaded {len(items)} items for produce lookup")
    else:
        log(f"note: {items_path} not found; produce keeps names only")

    t0 = time.time()
    scenery = build_scenery(lookup)
    wiki_count = len(scenery)

    provenance = None
    if not args.no_cache:
        cache = load_cache_objects(args.cache_dump)
        if cache:
            dumped = cache_dump_age(args.cache_dump)
            log(f"cache dump: {len(cache)} identifiable objects")
            if dumped:
                stamp, age = dumped
                provenance = {"cacheDumpedAt": stamp, "cacheAgeDays": age}
                # OSRS updates weekly, so anything older has likely drifted
                if age >= CACHE_STALE_DAYS:
                    log(f"  WARNING: dump is {age} days old ({stamp}); "
                        f"re-run ./gradlew :cache-tools:dumpObjects")
                else:
                    log(f"  dumped {stamp} ({age}d old)")
            scenery = join_cache(scenery, cache)
        else:
            log(f"note: no object dump at {args.cache_dump}; wiki only "
                f"(run ./gradlew :cache-tools:dumpObjects)")

    path = out / "all-scenery.json"
    scenery, inert = drop_inert(scenery, args.strict_actions)
    log(f"  dropped {len(inert)} objects with no actions"
        + ("" if args.strict_actions else
           "; kept those carrying produce or skillInfo"))
    path.write_text(json.dumps(write_shape(scenery, args.flat, provenance), indent=1))

    produce = {d["id"] for s in scenery for d in s["produce"] if d.get("id")}
    with_prod = sum(1 for s in scenery if s["produce"])
    with_info = sum(1 for s in scenery if s["skillInfo"])
    points = sum(len(L["spawns"]) for s in scenery for L in s["locations"])
    by_source = collections.Counter(s.get("source", "wiki") for s in scenery)
    log(f"wrote {path}  ({len(scenery)} objects, {time.time() - t0:.0f}s)")
    log(f"  {with_prod} yield produce ({len(produce)} distinct items), "
        f"{with_info} carry skill info, {points} located points")
    log(f"  source: {dict(by_source)} (wiki alone had {wiki_count})")


if __name__ == "__main__":
    main()
