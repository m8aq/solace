#!/usr/bin/env python3
"""
Build all-quests.json and all-locations.json from the live OSRS wiki.

Quests come from {{Infobox Quest}} plus {{Quest details}} (~435 pages) and
{{Quest rewards}} (~381). Locations come from {{Infobox Location}} (~1,134).

Quest requirements and rewards are prose, not fields:

    |requirements = * {{SCP|Quest|32}} [[Quest points]]
    * The ability to defeat a level 83 [[Elvarg|dragon]]

The {{SCP|Skill|Level}} calls inside are machine-readable, so skill gates and
XP rewards are extracted into `skillRequirements` / `experienceRewards`, and
the prose is kept alongside as `requirementsText`. Anything stated only in
English ("defeat a level 83 dragon") stays in the text -- deliberately, rather
than being half-parsed into something that looks authoritative.

Usage:
    python3 tools/build_osrs_quests.py --out data/wiki
"""

import argparse
import json
import re
import time
from pathlib import Path

from osrs_wiki import (
    wiki_title,
    fetch_wikitext, find_templates, log, pages_embedding,
    parse_map_features, parse_relative_locations, progress_bar, strip_markup, template_params,
    template_positionals, wiki_bool, wiki_file, wiki_int, wiki_list, wiki_num,
)


def parse_scp(text):
    """{{SCP|Skill|Level}} calls in prose -> [{skill, level}].

    Doubles as the XP-reward reader, where the second argument is an amount
    ("18,650") rather than a level -- callers decide which it is.
    """
    out = []
    for block in find_templates(text or "", "SCP"):
        pos = template_positionals(block)
        if len(pos) < 2:
            continue
        skill = strip_markup(pos[0])
        value = wiki_num(pos[1], None)
        if skill and value is not None:
            out.append({"skill": wiki_title(skill), "value": int(value)})
    return out


def _bullets(raw):
    """Wikitext bullet list -> [str], one entry per bullet."""
    out = []
    for line in (raw or "").splitlines():
        line = line.strip()
        if not line.startswith("*"):
            continue
        text = strip_markup(line.lstrip("*").strip())
        if text:
            out.append(text)
    return out


def parse_quests(texts):
    rows = []
    for title, text in texts.items():
        info = {}
        for block in find_templates(text, "Infobox Quest"):
            info = template_params(block)
            break
        details = {}
        for block in find_templates(text, "Quest details"):
            details = template_params(block)
            break
        rewards = {}
        for block in find_templates(text, "Quest rewards"):
            rewards = template_params(block)
            break
        if not info and not details:
            continue

        req = details.get("requirements", "")
        # in requirements the SCP second argument is a level; in rewards it is
        # an XP amount, so the same reader is labelled differently
        skill_reqs = [{"skill": s["skill"], "level": s["value"]}
                      for s in parse_scp(req)]
        xp_rewards = [{"skill": s["skill"], "experience": s["value"]}
                      for s in parse_scp(rewards.get("rewards", ""))]
        # `startmap = 3190,3360` is a bare pair, not a {{Map}} block
        start_pts = [(int(a), int(b)) for a, b in
                     re.findall(r"(\d{2,5})\s*,\s*(\d{2,5})",
                                details.get("startmap", ""))]

        rows.append({
            "name": strip_markup(info.get("name") or title),
            "page": title,
            "number": wiki_int(info.get("number")),
            "isMembers": wiki_bool(info.get("members")),
            "series": strip_markup(info.get("series", "")) or None,
            "difficulty": strip_markup(details.get("difficulty", "")) or None,
            "length": strip_markup(details.get("length", "")) or None,
            "questPoints": wiki_int(rewards.get("qp")),
            "start": strip_markup(details.get("start", "")) or None,
            "startCoords": [{"x": x, "y": y} for x, y in start_pts] or None,
            "description": strip_markup(details.get("description", "")) or None,
            "ironmanNote": strip_markup(details.get("ironman", "")) or None,
            "skillRequirements": skill_reqs,
            "requirementsText": _bullets(req),
            "itemsRequired": _bullets(details.get("items", "")),
            "itemsRecommended": _bullets(details.get("recommended", "")),
            "enemiesToDefeat": _bullets(details.get("kills", "")),
            "experienceRewards": xp_rewards,
            "rewardsText": _bullets(rewards.get("rewards", "")),
            "release": strip_markup(info.get("release", "")) or None,
        })
    return rows


def parse_locations(texts):
    rows = []
    for title, text in texts.items():
        for block in find_templates(text, "Infobox Location"):
            p = template_params(block)
            rows.append({
                "name": strip_markup(p.get("name") or title),
                "page": title,
                "isMembers": wiki_bool(p.get("members")),
                "type": wiki_title(strip_markup(p.get("type", ""))),
                "location": strip_markup(p.get("location", "")) or None,
                "teleport": strip_markup(p.get("teleport", "")) or None,
                "music": strip_markup(p.get("music", "")) or None,
                "race": wiki_title(strip_markup(p.get("race", ""))),
                "floors": wiki_int(p.get("floors")),
                "requirement": strip_markup(p.get("requirement", "")) or None,
                # These three are not flags, though they read like them:
                # `wilderness = 50-56` is a level range, `capital = [[Darkmeyer]]`
                # names a city, and `tellers = 4 [[Banker#5|bankers]];` is prose.
                # Modelled as booleans they were False in all 1,134 records.
                "wildernessLevels": strip_markup(p.get("wilderness", "")) or None,
                "capital": strip_markup(p.get("capital", "")) or None,
                "bankTellers": strip_markup(p.get("tellers", "")) or None,
                "hasDepositBox": wiki_bool(p.get("depositbox")),
                "hasPollBooth": wiki_bool(p.get("pollbooth")),
                "removal": strip_markup(p.get("removal", "")) or None,
                "leagueRegion": wiki_title(strip_markup(p.get("leagueregion", ""))),
                "aka": wiki_list(p.get("aka")),
                "image": wiki_file(p.get("image")),
                "spawns": parse_map_features(p.get("map", "")),
                # world adjacency: which places border this one
                "neighbours": parse_relative_locations(text),
            })
            break
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    titles = pages_embedding("Template:Quest details")
    log(f"quest pages: {len(titles)}")
    quests = parse_quests(fetch_wikitext(titles, progress=progress_bar("quests")))
    quests.sort(key=lambda r: (r["number"] if r["number"] is not None else 9999,
                               r["name"]))
    (out / "all-quests.json").write_text(json.dumps(quests, indent=1))
    gated = sum(1 for q in quests if q["skillRequirements"])
    xp = sum(1 for q in quests if q["experienceRewards"])
    log(f"wrote {out / 'all-quests.json'}  ({len(quests)} quests, "
        f"{time.time() - t0:.0f}s)")
    log(f"  {gated} with parsed skill gates, {xp} with parsed XP rewards")

    t0 = time.time()
    titles = pages_embedding("Template:Infobox Location")
    log(f"location pages: {len(titles)}")
    locs = parse_locations(fetch_wikitext(titles, progress=progress_bar("locations")))
    locs.sort(key=lambda r: r["name"])
    (out / "all-locations.json").write_text(json.dumps(locs, indent=1))
    mapped = sum(1 for l in locs if l["spawns"])
    log(f"wrote {out / 'all-locations.json'}  ({len(locs)} locations, "
        f"{time.time() - t0:.0f}s)")
    log(f"  {mapped} with coordinates")


if __name__ == "__main__":
    main()
