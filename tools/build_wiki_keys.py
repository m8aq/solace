#!/usr/bin/env python3
"""
Index the key schema of every dataset into docs/KEYS.md.

The datasets are wide -- 60 top-level keys on monsters, more once the nested
containers are opened -- and nothing else says which of them are actually
populated. A field present on 28.9% of records is a different thing to plan
around than one present on all of them, and only counting says which it is.

Every nested object is walked, not sampled, so the percentages are exact.

Usage:
    python3 tools/build_wiki_keys.py
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from osrs_wiki import load_dataset  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / "data" / "wiki"
OUT = REPO / "docs" / "KEYS.md"
MAX_DEPTH = 2


def typename(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "float"
    if isinstance(v, str):
        return "str"
    if isinstance(v, list):
        if not v:
            return "list[]"
        inner = sorted({typename(x) for x in v})
        return "list[%s]" % "|".join(inner)
    if isinstance(v, dict):
        return "obj"
    return type(v).__name__


def sample(v):
    try:
        s = json.dumps(v, ensure_ascii=False)
    except (TypeError, ValueError):
        s = str(v)
    s = " ".join(s.split())
    return s[:57] + "…" if len(s) > 58 else s


def uninformative(v):
    """Values that show nothing about a field.

    `v not in (None, "", [], {})` let 0, 0.0 and False through -- none of them
    equal any member of that tuple -- so a field whose first record happened to
    be zero was documented as `0`, which says neither what the field means nor
    what it can hold. Zero is a real value; it is just a bad example.
    """
    if v is None or isinstance(v, bool) and v is False:
        return True
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return v == 0
    if isinstance(v, (str, list, dict, tuple)):
        return len(v) == 0
    return False


def better_example(v):
    """An example is better if it shows more about the field.

    Containers rank by how much they hold, so a list of populated objects
    beats a list of empty ones. Scalars needed a rank too: without one every
    string tied and the first record won again, which made the interface id
    `"0:0"` -- a real value, group 0 child 0, and useless for learning the
    format next to `"161:45"`. A value whose digits are all zero teaches
    nothing that a non-zero one does not.
    """
    # (primary, tiebreak). The tiebreak is size, capped so one long outlier
    # cannot win: among equally populated values the fuller one shows more of
    # the range -- "161:45" over "1:1".
    if isinstance(v, list):
        return sum(1 for x in v if not uninformative(x)), min(len(v), 20)
    if isinstance(v, dict):
        return (sum(1 for x in v.values() if not uninformative(x)),
                min(len(v), 20))
    if isinstance(v, str):
        if any(c.isalpha() for c in v):
            return 2, min(len(v), 40)
        # a numeric string ranks by how many of its parts are non-zero, so a
        # composite id shows both halves populated: "161:45" over "0:1" over
        # "0:0". Ranking only zero-vs-nonzero left "0:1" winning on a tie.
        parts = re.findall(r"\d+", v)
        primary = 1 + sum(1 for p in parts if int(p) != 0) if parts else 2
        return primary, min(len(v), 40)
    return 1, 0


def profile(records):
    """-> {key: {count, types: Counter, example, children}} for one record level."""
    fields = defaultdict(lambda: {"count": 0, "types": Counter(), "example": None,
                                  "rank": (0, 0), "fallback": None,
                                  "kids": [], "kidn": 0})
    for rec in records:
        if not isinstance(rec, dict):
            continue
        for k, v in rec.items():
            f = fields[k]
            f["count"] += 1
            f["types"][typename(v)] += 1
            if not uninformative(v):
                rank = better_example(v)
                # keep scanning rather than taking the first hit: the first
                # populated value is often the thinnest one
                if rank > f["rank"]:
                    f["example"], f["rank"] = sample(v), rank
            elif f["fallback"] is None and v is not None:
                f["fallback"] = sample(v)
            if isinstance(v, dict):
                f["kids"].append(v)
                f["kidn"] += 1
            elif isinstance(v, list):
                kids = [x for x in v if isinstance(x, dict)]
                if kids:
                    f["kids"].extend(kids)
                    f["kidn"] += 1
    return fields


def render(fields, total, depth, out):
    pad = "" if depth == 0 else ""
    out.append(pad + "| key | coverage | types | example |")
    out.append(pad + "|---|---|---|---|")
    for k in sorted(fields, key=lambda k: (-fields[k]["count"], k)):
        f = fields[k]
        pct = 100.0 * f["count"] / total if total else 0
        cov = "100%" if pct >= 99.995 else ("%.1f%%" % pct if pct >= 0.1 else "<0.1%")
        types = ", ".join(t for t, _ in f["types"].most_common(4))
        ex = (f["example"] or f["fallback"] or "").replace("|", "\\|")
        out.append("| `%s` | %s (%s) | %s | %s |" % (k, cov, f"{f['count']:,}", types, ex))
    out.append("")

    if depth >= MAX_DEPTH - 1:
        return
    for k in sorted(fields, key=lambda k: (-fields[k]["count"], k)):
        f = fields[k]
        if not f["kids"]:
            continue
        sub = profile(f["kids"])
        if not sub:
            continue
        out.append("<details><summary><code>%s</code> — %s nested keys across %s "
                   "objects</summary>\n" % (k, len(sub), f"{len(f['kids']):,}"))
        render(sub, len(f["kids"]), depth + 1, out)
        out.append("</details>\n")


def main():
    paths = sorted(ROOT.glob("all-*.json"))
    out = ["# Key index — `data/wiki/`", ""]
    out.append("Every key in every `all-*.json`, with how many records carry it, the "
               "value types seen, and a representative value as an example — the most populated one seen, never 0, null or an empty container unless the field holds nothing else. Nested "
               "objects and arrays-of-objects are profiled one level deep in the "
               "collapsed blocks.")
    out.append("")
    out.append("Generated by `tools/build_wiki_keys.py` — rerun it rather than editing "
               "this file by hand. Coverage is out of the records in that file; a key "
               "at less than 100% is absent from the rest, not null in them.")
    out.append("")

    toc, bodies = [], []
    for p in paths:
        records = load_dataset(p)
        raw = json.loads(p.read_text())
        shape = "compact (page-keyed object)" if isinstance(raw, dict) else "list of records"
        total = len(records)
        fields = profile(records)
        anchor = p.name.replace(".", "").replace("-", "-")
        toc.append("- [`%s`](#%s) — %s records, %d top-level keys"
                   % (p.name, anchor, f"{total:,}", len(fields)))
        bodies.append("## `%s`" % p.name)
        bodies.append("")
        bodies.append("%s records · %.1f MB on disk · %s"
                      % (f"{total:,}", p.stat().st_size / 1e6, shape))
        bodies.append("")
        buf = []
        render(fields, total, 0, buf)
        bodies.extend(buf)
        print("%-26s %7d records %3d keys" % (p.name, total, len(fields)), file=sys.stderr)

    out.extend(toc)
    out.append("")
    out.extend(bodies)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT.relative_to(REPO)}", file=sys.stderr)


main()
