#!/usr/bin/env python3
"""
Fold the game cache's type tables into the datasets.

The wiki documents what players see. The cache holds what the client runs, and
most of it has no wiki page at all: varbits, varps, animation ids, interface
components, sprites. A plugin needs those and cannot get them from any wiki
query, which makes this the one source Bucket cannot replace.

Two kinds of merge, split by whether the wiki already covers the subject:

  Not on the wiki -- varbits, varps, animations, spotanims, sprites, sounds,
  interfaces, inventories, tables, rows. Written as their own datasets, since
  there is nothing to merge them into.

  Already on the wiki -- objects, npcs, scenery. Only the cache's *internal
  name* is added, as `cacheName`. `molanisk`, `mcannontoolkit` and
  `templetrek_snake_1` appear nowhere on the wiki, and they are what a plugin
  matches against. The stats and prose stay as the wiki has them.

Types come from the mcp-osrs data directory, which ships them as `id<TAB>name`
(`group:child<TAB>group:child` for interfaces and tables).

Usage:
    python3 tools/build_osrs_cache.py --list
    python3 tools/build_osrs_cache.py --out data/wiki
    python3 tools/build_osrs_cache.py --enrich data/wiki
"""

import argparse
import glob
import json
import os
import re
from pathlib import Path

from osrs_wiki import load_dataset, log

# Ships with the mcp-osrs package; the npx path varies by machine, so glob it.
DEFAULT_GLOB = str(Path.home() / ".npm" / "_npx" / "*" / "node_modules" /
                   "@jayarrowz" / "mcp-osrs" / "dist" / "data")

# Cache types with no wiki equivalent -> their own dataset.
STANDALONE = {
    "varbittypes": ("all-varbits.json", "varbit"),
    "varptypes": ("all-varps.json", "varp"),
    "seqtypes": ("all-animations.json", "animation"),
    "spottypes": ("all-spotanims.json", "spotanim"),
    "spritetypes": ("all-sprites.json", "sprite"),
    "soundtypes": ("all-sounds.json", "sound"),
    "iftypes": ("all-interfaces.json", "interface"),
    "invtypes": ("all-inventories.json", "inventory"),
    "tabletypes": ("all-tables.json", "table"),
    "rowtypes": ("all-rows.json", "row"),
}

# Cache types the wiki already covers -> contribute only the internal name.
ENRICH = {
    "objtypes": ("all-items.json", "id"),
    "npctypes": ("all-npcs.json", "id"),
    "loctypes": ("all-scenery.json", "objectId"),
}


def find_data(path=None):
    if path:
        return Path(path)
    hits = sorted(glob.glob(DEFAULT_GLOB))
    return Path(hits[-1]) if hits else None


def read_types(directory, name):
    """`id<TAB>name` -> [{id, name}], keeping composite ids as strings."""
    path = Path(directory) / f"{name}.txt"
    if not path.exists():
        return []
    out = []
    for line in path.read_text(errors="replace").splitlines():
        if "\t" not in line:
            continue
        ident, label = line.split("\t", 1)
        ident, label = ident.strip(), label.strip()
        if not ident or not label:
            continue
        record = {"name": label}
        if ":" in ident:
            # an interface component: group id and child id
            group, _, child = ident.partition(":")
            record["id"] = ident
            record["group"] = int(group) if group.isdigit() else group
            record["child"] = int(child) if child.isdigit() else child
        else:
            record["id"] = int(ident) if ident.isdigit() else ident
        out.append(record)
    return out


def write_standalone(directory, out_dir):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for source, (filename, kind) in sorted(STANDALONE.items()):
        rows = read_types(directory, source)
        if not rows:
            log(f"  {kind:<10} {source}.txt missing")
            continue
        for row in rows:
            row["kind"] = kind
        (out_dir / filename).write_text(json.dumps(rows, indent=1))
        total += len(rows)
        log(f"  {kind:<10}{len(rows):>7,} -> {filename}")
    return total


def enrich(directory, data_dir):
    """Add `cacheName` where the wiki and the cache describe the same id."""
    data_dir = Path(data_dir)
    for source, (filename, key) in sorted(ENRICH.items()):
        path = data_dir / filename
        if not path.exists():
            log(f"  {filename} not present; skipped")
            continue
        by_id = {r["id"]: r["name"] for r in read_types(directory, source)
                 if isinstance(r.get("id"), int)}
        if not by_id:
            log(f"  {source}.txt missing; skipped")
            continue
        records = load_dataset(path)
        hit = 0
        for record in records:
            name = by_id.get(record.get(key))
            if name:
                record["cacheName"] = name
                hit += 1
        path.write_text(json.dumps(records, indent=1))
        log(f"  {filename:<22}{hit:>7,}/{len(records):,} records "
            f"gained cacheName from {source}.txt")


def wiki_vars():
    """{("varbit"|"varp", index): {wikiName, content, page}} from the wiki.

    The cache gives a varbit an id and an internal symbol; it says nothing
    about what the bit means. The wiki documents 2,781 varbits and 116 varps
    at RuneScape:Varbit/<n> and RuneScape:Varplayer/<n>, each with a readable
    name and the content it belongs to -- 1955 is TEMPLE_TREKKING_POINTS,
    which is the difference between an id and a fact.

    Bucket holds those pages in one table, so this is a single query rather
    than 2,897 page fetches.
    """
    from build_osrs_bucket import bucket
    out = {}
    for row in bucket("varbit", ["page_name", "index", "name", "content"]):
        page = row.get("page_name") or ""
        kind = ("varbit" if ":Varbit/" in page else
                "varp" if ":Varplayer/" in page else None)
        # The id comes from the page title, not the `index` field. `index` is
        # the infobox parameter and is not always filled in --
        # RuneScape:Varbit/12296 carries index 0, which keyed the whole
        # A Kingdom Divided entry onto varbit 0 and mislabelled it.
        tail = page.rsplit("/", 1)[-1]
        if kind is None or not tail.isdigit():
            continue
        out[(kind, int(tail))] = {
            "wikiName": first_str(row.get("name")),
            "content": first_str(row.get("content")),
            "wikiPage": page,
        }
    describe(out, [r.get("page_name") or "" for r in
                   bucket("varbit", ["page_name"])])
    return out


# `class` says how to read the value -- a Counter holds a score, a Switch a
# state, a Bitmap packed flags -- and editors leave the template's own comment
# in the field often enough to need cleaning.
VAR_CLASS = re.compile(r"^\|\s*class\s*=\s*([A-Za-z]+)", re.M)
# <pre> and <syntaxhighlight> both appear on these pages
CODE_BLOCK = re.compile(
    r"<\s*(pre|code|syntaxhighlight)\b[^>]*>(.*?)<\s*/\s*\1\s*>",
    re.S | re.I)
KNOWN_CLASSES = {"switch": "Switch", "enum": "Enum", "counter": "Counter",
                 "bitmap": "Bitmap", "other": "Other", "swotch": "Switch"}


def describe(docs, pages):
    """Add `varClass` and `description` from each page's wikitext.

    Neither is in Bucket's table, which carries only name, content and index.
    The description is the page body once the infoboxes are removed -- "This
    varbit tracks the score of the current Temple Trekking run" -- and is the
    only place that says what the value actually means.

    Wikitext rather than prop=extracts: extracts would give the prose without
    any stripping, but batches 20 titles where wikitext batches 50, and the
    class parameter needs the source anyway. One pass for both.
    """
    from osrs_wiki import fetch_wikitext, strip_markup
    texts = fetch_wikitext([p for p in pages if p])
    for page, raw in texts.items():
        tail = page.rsplit("/", 1)[-1]
        kind = ("varbit" if ":Varbit/" in page else
                "varp" if ":Varplayer/" in page else None)
        if kind is None or not tail.isdigit():
            continue
        record = docs.get((kind, int(tail)))
        if record is None:
            continue
        found = VAR_CLASS.search(raw)
        if found:
            record["varClass"] = KNOWN_CLASSES.get(found.group(1).lower())

        # Code blocks come out before the prose does. Varplayer/447 documents
        # its follower masks as a <pre> block of Java constants, and
        # strip_markup collapses whitespace -- which turned twelve named masks
        # into one unreadable line. Newlines are the content here.
        body = raw
        code = [m.group(2).strip() for m in CODE_BLOCK.finditer(body)
                if m.group(2).strip()]
        if code:
            record["code"] = code
            body = CODE_BLOCK.sub(" ", body)

        # what is left once every template is gone; a var page is an infobox,
        # some prose, and a {{Similar Vars}} footer
        for _ in range(6):
            stripped = re.sub(r"\{\{[^{}]*\}\}", "", body, flags=re.S)
            if stripped == body:
                break
            body = stripped
        prose = strip_markup(body).strip()
        if len(prose) > 20:
            record["description"] = prose


def first_str(value):
    if isinstance(value, list):
        value = value[0] if value else None
    value = str(value).strip() if value is not None else ""
    return value or None


def merge_vars(out_dir):
    """Fold the wiki's var documentation into the cache-built datasets."""
    try:
        docs = wiki_vars()
    except Exception as exc:
        log(f"  wiki var lookup failed ({type(exc).__name__}); "
            "cache names only")
        return
    for kind, filename in (("varbit", "all-varbits.json"),
                           ("varp", "all-varps.json")):
        path = Path(out_dir) / filename
        if not path.exists():
            continue
        records = json.loads(path.read_text())
        hit = 0
        for record in records:
            doc = docs.get((kind, record.get("id")))
            if doc:
                record.update(doc)
                hit += 1
        path.write_text(json.dumps(records, indent=1))
        log(f"  {filename:<20}{hit:>7,}/{len(records):,} gained the wiki's "
            f"name and content")


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--data", help="mcp-osrs data directory")
    ap.add_argument("--out", help="write the cache-only datasets here")
    ap.add_argument("--enrich", metavar="DIR",
                    help="add cacheName to the all-*.json in this directory")
    ap.add_argument("--no-wiki-vars", action="store_true",
                    help="skip folding the wiki's varbit/varp docs in")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    directory = find_data(args.data)
    if not directory or not directory.is_dir():
        raise SystemExit(f"no cache type data found (looked in {DEFAULT_GLOB})")

    if args.list:
        log(f"cache types in {directory}")
        for source in sorted(set(STANDALONE) | set(ENRICH)):
            rows = read_types(directory, source)
            where = STANDALONE.get(source, ENRICH.get(source))[0]
            log(f"  {source:<16}{len(rows):>8,}  -> {where}")
        return

    if args.out:
        log(f"cache-only datasets from {directory}")
        total = write_standalone(directory, args.out)
        log(f"  {total:,} records across {len(STANDALONE)} types")
        if not args.no_wiki_vars:
            log("\nmerging the wiki's varbit and varp documentation")
            merge_vars(args.out)
    if args.enrich:
        log(f"\nenriching {args.enrich}")
        enrich(directory, args.enrich)
    if not args.out and not args.enrich:
        raise SystemExit("give --out, --enrich, or --list")


if __name__ == "__main__":
    main()
