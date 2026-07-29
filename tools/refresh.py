#!/usr/bin/env python3
"""
Rebuild every dataset, in dependency order.

There were five entry points and no single way to run them, which meant a
refresh depended on remembering which builder owned what and which had to run
first. Three datasets -- locations, prayers and shops -- had no automated
coverage at all, because Bucket has no table for them and the wikitext
builders that do were never wired into anything.

The order is not arbitrary:

  1. dump          re-read object definitions out of the local game cache,
                   so the scenery join below uses today's game and not the
                   dump left over from whenever it was last run
  2. Bucket        thirteen datasets from the wiki's structured store, with
                   scenery joined against that fresh dump; then the page-to-id
                   mappings, which are an independent check on those ids
  3. wikitext      the three Bucket cannot answer, which read all-items.json
                   and all-npcs.json and so must follow step 2
  4. cache         ten cache-only datasets, then cacheName folded into items,
                   npcs and scenery, and the wiki's varbit and varp
                   documentation folded into the two var datasets -- all of
                   which need step 2 to have written the files first
  5. index         the category tree, independent of the datasets
  6. keys          docs/KEYS.md, last, because it profiles what is on disk
  7. validate      value-shape checks over the result

A stage that fails does not stop the run by default: a wiki outage during
shops should not cost the cache datasets too. Failures are reported at the
end, and --strict exits non-zero on any of them.

Usage:
    python3 tools/refresh.py
    python3 tools/refresh.py --only bucket cache
    python3 tools/refresh.py --skip validate --strict
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
DATA = REPO / "data" / "wiki"

# The object dump is the one input that is not fetched over the network: it is
# read out of the local RuneLite cache by a Gradle task. It goes stale on every
# game update, so a refresh re-runs it rather than reusing whatever is on disk.
GRADLE = ["!gradle", ":cache-tools:dumpObjects"]

# (stage, description, argv). Each runs as its own process so one blowing up
# cannot take the rest of the run with it.
def stages(data):
    out = str(data)
    return [
        ("dump", "re-dump object definitions from the game cache",
         GRADLE),
        ("bucket", "13 wiki datasets from Bucket, scenery joined to the dump",
         ["build_osrs_bucket.py", "--all", "--out", out]),
        ("pageids", "page-to-id mappings for items, npcs and objects",
         ["build_osrs_bucket.py", "--page-ids", "--out", out]),
        ("magic", "spells and prayers (no Bucket table for prayers)",
         ["build_osrs_magic.py", "--out", out]),
        ("quests", "quests and locations (no Bucket table for locations)",
         ["build_osrs_quests.py", "--out", out]),
        ("shops", "shops, merged across npc and shop pages",
         ["build_osrs_shops.py", "--out", out]),
        ("cache", "10 cache datasets, cacheName, and the wiki's var docs",
         ["build_osrs_cache.py", "--out", out, "--enrich", out]),
        ("index", "the category tree",
         ["build_wiki_index.py"]),
        ("keys", "docs/KEYS.md",
         ["build_wiki_keys.py"]),
        ("validate", "value-shape checks",
         ["validate_wiki_data.py", "--data", out]),
    ]


def run(stage, argv):
    started = time.time()
    if argv[0] == "!gradle":
        cmd = [str(REPO / "gradlew"), *argv[1:]]
        cwd = str(REPO)
    else:
        cmd = [sys.executable, str(TOOLS / argv[0]), *argv[1:]]
        cwd = None
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
    tail = [line for line in (result.stderr or result.stdout).splitlines()
            if line.strip()]
    return result.returncode, time.time() - started, tail[-1] if tail else ""


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--data", default=str(DATA), help="dataset directory")
    ap.add_argument("--only", nargs="+", metavar="STAGE")
    ap.add_argument("--skip", nargs="+", default=[], metavar="STAGE")
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero if any stage failed")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    plan = stages(Path(args.data))
    if args.list:
        for name, why, argv in plan:
            label = "gradlew " + argv[1] if argv[0] == "!gradle" else argv[0]
            print(f"  {name:<10}{label:<32} {why}")
        return

    chosen = [s for s in plan
              if (not args.only or s[0] in args.only) and s[0] not in args.skip]
    if not chosen:
        raise SystemExit("no stages selected")

    print(f"refreshing {args.data} — {len(chosen)} stages\n")
    failed, began = [], time.time()
    for name, why, argv in chosen:
        print(f"  {name:<10} {why} … ", end="", flush=True)
        code, took, last = run(name, argv)
        if code == 0:
            print(f"ok {took:5.0f}s   {last[:60]}")
        else:
            failed.append(name)
            print(f"FAILED {took:4.0f}s   {last[:60]}")

    print(f"\n{len(chosen) - len(failed)}/{len(chosen)} stages ok "
          f"in {time.time() - began:.0f}s")
    if failed:
        print(f"failed: {', '.join(failed)}")
        print("rerun one with: python3 tools/refresh.py --only " + failed[0])
        if args.strict:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
