#!/usr/bin/env python3
"""
Build all-construction.json from the live OSRS wiki.

Reads {{Infobox Construction}} (~520 pages) for player-owned-house furniture:
the built object's scenery ids and the Construction level, XP, room and hotspot
needed to build it.

The ids here are *scenery* ids, not inventory item ids, which is why furniture
gets its own file rather than joining all-items.json.

With --enrich-recipes (the default) this also patches all-recipes.json,
attaching objectIds to Construction recipe outputs that have no item id —
furniture is built, not obtained, so those outputs carry only a name until
matched here. Run after build_osrs_items.py.

Usage:
    python3 tools/build_osrs_construction.py --out data/wiki
    python3 tools/build_osrs_construction.py --out data/wiki --no-enrich-recipes
"""

import argparse
import json
import re
import time
from pathlib import Path

from osrs_wiki import (
    wiki_title,
    parse_ids,
    compact_by_page, fetch_wikitext, find_templates, load_dataset, log,
    pages_embedding, progress_bar, strip_markup, template_params, versioned,
    wiki_bool, wiki_file, wiki_int, wiki_list,
)


def parse_construction_page(title, text):
    """{{Infobox Construction}} -> [{objectId, level, experience, ...}]."""
    rows = []
    for block in find_templates(text, "Infobox Construction"):
        p = template_params(block)
        idxs = sorted({m.group(1) for k in p
                       for m in [re.fullmatch(r"id(\d*)", k)] if m})
        for idx in idxs:
            v = versioned(p, idx)
            base = {
                "name": strip_markup(v("name") or title),
                "page": title,
                "version": wiki_title(strip_markup(v("version", ""))),
                "furnitureName": strip_markup(v("furniturename", "")) or None,
                # `experience = 2,230` — wiki_int strips the thousands comma
                "level": wiki_int(v("level")),
                "experience": wiki_int(v("experience")),
                "room": wiki_title(strip_markup(v("room", ""))),
                "hotspot": wiki_title(strip_markup(v("hotspot", ""))),
                # `flatpack` is whether the furniture can be flatpacked;
                # `itemid` is the item used as its game-guide icon, which is
                # not the same thing (per Template:Infobox Construction/doc)
                "canFlatpack": wiki_bool(v("flatpack")),
                # `itemid = 8255,8256,8257` is a list. wiki_int strips commas
                # (right for "2,230") and welded it into 825582568257, so ids
                # go through parse_ids like every other id field.
                "iconItemIds": parse_ids(v("itemid")),
                "options": wiki_list(v("options")),
                "examine": strip_markup(v("examine", "")) or None,
                "icon": wiki_file(v("icon")),
                "image": wiki_file(v("image")),
                "release": strip_markup(v("release", "")) or None,
                "releaseUpdate": strip_markup(v("update", "")) or None,
            }
            for one in parse_ids(p.get(f"id{idx}")):
                rows.append({**base, "objectId": int(one)})
    return rows


def build_construction():
    titles = pages_embedding("Template:Infobox Construction")
    log(f"construction pages: {len(titles)}")
    texts = fetch_wikitext(titles, progress=progress_bar("construction"))

    rows, seen = [], set()
    for title, text in texts.items():
        for row in parse_construction_page(title, text):
            if row["objectId"] in seen:
                continue
            seen.add(row["objectId"])
            rows.append(row)
    rows.sort(key=lambda r: r["objectId"])
    return rows


def enrich_recipes(recipes, construction):
    """Fill objectIds on Construction recipe outputs. Returns (filled, left)."""
    by_page = {}
    for row in construction:
        by_page.setdefault(row["page"].lower(), []).append(row)

    filled = left = 0
    for recipe in recipes:
        if not any(s.get("name") == "Construction"
                   for s in (recipe.get("skills") or [])):
            continue
        # Match on the recipe's page, not the output name: a page like
        # "Incense burner (Oak)" produces an output named "Oak incense burners",
        # so the page is the reliable key (and matches strictly more rows).
        matches = by_page.get((recipe.get("page") or "").lower())
        for out in recipe.get("outputs") or []:
            if "id" in out:
                continue
            if not matches:
                left += 1
                continue
            out["objectIds"] = sorted({m["objectId"] for m in matches})
            icons = {i for m in matches for i in (m["iconItemIds"] or [])}
            if icons:
                out["iconItemIds"] = sorted(icons)
            filled += 1
    return filled, left


COMPACT_FIELDS = ("name", "furnitureName", "level", "experience", "room",
                  "hotspot", "canFlatpack", "iconItemIds", "icon", "release",
                  "releaseUpdate", "options")


def write_shape(records, flat=False):
    """Page-identical fields move into a `pages` side table (see osrs_wiki)."""
    if flat:
        return records
    recs, pages = compact_by_page(records, COMPACT_FIELDS)
    return {"pages": pages, "records": recs}


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    ap.add_argument("--recipes", help="all-recipes.json path "
                                      "(default: <out>/all-recipes.json)")
    ap.add_argument("--flat", action="store_true",
                    help="write a plain array instead of the compact "
                         "`pages`/`records` shape")
    ap.add_argument("--no-enrich-recipes", action="store_true",
                    help="do not write objectIds back into all-recipes.json")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    construction = build_construction()
    path = out / "all-construction.json"
    path.write_text(json.dumps(write_shape(construction, args.flat), indent=1))

    flatpackable = sum(1 for c in construction if c["canFlatpack"])
    log(f"wrote {path}  ({len(construction)} furniture, {time.time() - t0:.0f}s)")
    log(f"  {flatpackable} flatpackable, "
        f"{len({c['page'] for c in construction})} distinct pages")

    if args.no_enrich_recipes:
        return
    recipes_path = Path(args.recipes) if args.recipes else out / "all-recipes.json"
    if not recipes_path.exists():
        log(f"note: {recipes_path} not found; skipping recipe enrichment")
        return
    recipes = load_dataset(recipes_path)
    filled, left = enrich_recipes(recipes, construction)
    # recipes are written flat (hoisting their scalars costs more than it
    # saves), but preserve whatever shape is actually on disk
    if isinstance(json.loads(recipes_path.read_text()), dict):
        recipes_path.write_text(json.dumps({"pages": {}, "records": recipes},
                                           indent=1))
    else:
        recipes_path.write_text(json.dumps(recipes, indent=1))
    log(f"  patched {recipes_path}: objectIds on {filled} outputs, "
        f"{left} still unresolved (no Construction page — mounted trophies "
        f"and non-item outputs)")


if __name__ == "__main__":
    main()
