#!/usr/bin/env python3
"""
Check the shape of values in the built datasets.

The other checks look at structure: audit_wiki_fields.py asks which template
parameters we read, sweep_wiki_templates.py asks which templates exist at all,
and test_osrs_wiki.py pins parser behaviour. None of them notice when a field
we do read holds a *wrong-looking value* -- `"12 minutes6 minutes"` sat in the
data for a full build because a stripped `<br/>` welded two lines together, and
every structural check was green.

This is the missing layer: it reads the finished JSON and flags values that
cannot be right regardless of how they were parsed.

    welded      prose run together with no separator (advisory: player names
                and proper nouns look identical, so this never reaches zero)
    coords      tiles outside the world, or an impossible plane
    ids         item/object ids that resolve to nothing
    collisions  one id claimed by both an npc and a different monster
    duplicates  the same id twice in one dataset
    empty       records missing the field that identifies them
    numeric     negative levels, xp or quantities

Findings are advisory by default; `--strict` exits non-zero so it can gate a
build.

Usage:
    python3 tools/validate_wiki_data.py
    python3 tools/validate_wiki_data.py --data data/wiki --strict
"""

import argparse
import collections
import json
import re
import sys
from pathlib import Path

from osrs_wiki import load_dataset

# OSRS world coordinates. The window is deliberately wide: the point is to
# catch a 0 or a five-digit accident, not to police the map. Instanced areas
# (mapID set) use their own origin -- Cerberus' Lair sits at y=1250 -- so those
# points are only checked for sanity, not for surface bounds.
X_RANGE = (700, 4300)
Y_RANGE = (1000, 13000)
PLANES = (0, 1, 2, 3)

# Fields that hold prose or lists, where two words colliding means a lost
# separator. Identifier-ish fields are excluded: "TzHaar-Ket" and "MacGrubor"
# are legitimately camel-cased.
PROSE_FIELDS = {
    "location", "examine", "effect", "description", "time", "yield",
    "payment", "start", "requirement", "ticksNote", "notes", "specialty",
    "maxHit", "attackStyle", "poisonous", "quest",
}
# lowercase or ')' immediately followed by an uppercase letter or digit
WELD = re.compile(r"[a-z)][A-Z0-9(]")

# Proper nouns the game spells with an internal capital. Structurally these are
# indistinguishable from a lost separator, so they have to be named.
CAMEL_WORDS = re.compile(
    r"\b(RuneScape|ScapeRune|OldSchool|GoingNowhere|TzHaar|TzTok|TzKal|"
    r"KruK|LumBridge|McGrubor|MacGrubor|DeadMan|LeaguePoints|HiScores|"
    r"JalTok|JalXil|JalImKot|YtHurKot|KetZek|MejJal|SolHeredit|"
    r"DagannothRex|GodWars|BarrowsBrothers)\b")


# Heuristic checks that cannot be driven to zero, so they never fail a build.
ADVISORY = {"welded"}


class Report:
    def __init__(self):
        self.findings = collections.defaultdict(list)

    def blocking(self):
        return sum(len(v) for (_, kind), v in self.findings.items()
                   if kind not in ADVISORY)

    def add(self, dataset, kind, detail):
        self.findings[(dataset, kind)].append(detail)

    def total(self):
        return sum(len(v) for v in self.findings.values())

    def show(self, limit=4):
        if not self.findings:
            print("no findings")
            return
        width = max(len(d) for d, _ in self.findings)
        for (dataset, kind), items in sorted(self.findings.items()):
            print(f"\n{dataset:{width}}  {kind}  ({len(items)})")
            for detail in items[:limit]:
                print(f"    {detail}")
            if len(items) > limit:
                print(f"    ... {len(items) - limit} more")


def walk_coords(value, path=""):
    """Yield (path, point) for every {x, y} dict nested anywhere in `value`."""
    if isinstance(value, dict):
        if "x" in value and "y" in value:
            yield path, value
        for key, inner in value.items():
            yield from walk_coords(inner, f"{path}.{key}" if path else key)
    elif isinstance(value, list):
        for item in value:
            yield from walk_coords(item, path)


def check_coords(report, dataset, records):
    for rec in records:
        ident = rec.get("id") or rec.get("objectId") or rec.get("name")
        for path, pt in walk_coords(rec):
            x, y, plane = pt.get("x"), pt.get("y"), pt.get("plane", 0)
            if not isinstance(x, int) or not isinstance(y, int):
                report.add(dataset, "coords", f"{ident}: non-integer {pt}")
            elif not (X_RANGE[0] <= x <= X_RANGE[1]):
                report.add(dataset, "coords", f"{ident}: x={x} out of range ({path})")
            elif not (Y_RANGE[0] <= y <= Y_RANGE[1]):
                report.add(dataset, "coords", f"{ident}: y={y} out of range ({path})")
            if plane not in PLANES:
                report.add(dataset, "coords", f"{ident}: plane={plane} ({path})")


def check_welded(report, dataset, records):
    for rec in records:
        ident = rec.get("id") or rec.get("objectId") or rec.get("name")
        for key, value in rec.items():
            if key not in PROSE_FIELDS or not isinstance(value, str):
                continue
            # a genuine sentence has spaces; a welded one often has none around
            # the collision, so only flag when the two sides are both wordy
            cleaned = CAMEL_WORDS.sub("", value)
            for m in WELD.finditer(cleaned):
                before, after = cleaned[:m.start() + 1], cleaned[m.start() + 1:]
                left = before.split()[-1] if before.split() else ""
                right = after.split()[0] if after.split() else ""
                if len(left) >= 4 and len(right) >= 4:
                    report.add(dataset, "welded",
                               f"{ident}: {key}={value[:70]!r}")
                    break


def check_ids(report, dataset, records, item_ids, object_ids):
    for rec in records:
        ident = rec.get("id") or rec.get("objectId") or rec.get("name")
        for key in ("drops", "produce", "inventory", "inputs", "outputs"):
            for entry in rec.get(key) or []:
                if not isinstance(entry, dict):
                    continue
                iid = entry.get("id")
                if iid is not None and iid not in item_ids:
                    report.add(dataset, "ids",
                               f"{ident}: {key} references unknown item {iid}")
        for oid in rec.get("objectIds") or []:
            if oid not in object_ids:
                report.add(dataset, "ids",
                           f"{ident}: unknown object {oid}")


def check_id_collisions(report, datasets):
    """NPCs and monsters share one id space, so a clash is an error somewhere.

    This is how 173 fabricated ids surfaced: a `hist1` marker on a removed NPC
    was read as id 1, which belongs to a live monster.
    """
    npcs = {r["id"]: r.get("name") for r in datasets.get("all-npcs", [])}
    for rec in datasets.get("all-monsters", []):
        other = npcs.get(rec["id"])
        if other is not None and other != rec.get("name"):
            report.add("all-monsters", "collisions",
                       f"id {rec['id']}: monster {rec.get('name')!r} "
                       f"vs npc {other!r}")


def check_duplicates(report, dataset, records):
    for key in ("id", "objectId"):
        seen = collections.Counter(r[key] for r in records if key in r)
        for value, count in seen.items():
            if count > 1:
                report.add(dataset, "duplicates", f"{key}={value} appears {count}x")


# Recipes and construction rows are identified by their outputs, not a name.
NAMELESS_DATASETS = {"all-recipes"}


def check_empty(report, dataset, records):
    if dataset in NAMELESS_DATASETS:
        return
    for rec in records:
        # cache-only objects are identified by id and actions; the game data
        # simply has no name for them
        if rec.get("source") == "cache":
            continue
        if not (rec.get("name") or "").strip():
            ident = rec.get("id") or rec.get("objectId") or "?"
            report.add(dataset, "empty", f"{ident}: no name")


# Only negatives are impossible. A level of 0 is valid for a non-combat NPC,
# and 0 xp is valid for an unfinished potion.
NUMERIC_FLOORS = {"level": 0, "experience": 0, "xp": 0, "quantity": 0,
                  "value": 0, "hitpoints": 0, "questPoints": 0, "drain": 0}


def check_numeric(report, dataset, records):
    for rec in records:
        ident = rec.get("id") or rec.get("objectId") or rec.get("name")
        for key, floor in NUMERIC_FLOORS.items():
            val = rec.get(key)
            if isinstance(val, (int, float)) and val < floor:
                report.add(dataset, "numeric", f"{ident}: {key}={val}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--data", default="data/wiki", help="dataset directory")
    ap.add_argument("--limit", type=int, default=4,
                    help="examples to print per finding type")
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero when anything is flagged")
    args = ap.parse_args()

    root = Path(args.data)
    files = sorted(root.glob("all-*.json"))
    if not files:
        sys.exit(f"no datasets in {root}")

    datasets = {f.stem: load_dataset(f) for f in files}
    item_ids = {r["id"] for r in datasets.get("all-items", []) if "id" in r}
    object_ids = {r["objectId"] for r in datasets.get("all-scenery", [])
                  if "objectId" in r}

    report = Report()
    check_id_collisions(report, datasets)
    for name, records in datasets.items():
        check_coords(report, name, records)
        check_welded(report, name, records)
        check_ids(report, name, records, item_ids, object_ids)
        check_duplicates(report, name, records)
        check_empty(report, name, records)
        check_numeric(report, name, records)

    total_records = sum(len(r) for r in datasets.values())
    print(f"checked {total_records} records across {len(datasets)} datasets")
    report.show(args.limit)
    advisory = report.total() - report.blocking()
    print(f"\n{report.total()} finding(s) "
          f"({report.blocking()} blocking, {advisory} advisory)")
    if args.strict and report.blocking():
        sys.exit(1)


if __name__ == "__main__":
    main()
