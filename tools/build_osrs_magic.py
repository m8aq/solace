#!/usr/bin/env python3
"""
Build all-spells.json and all-prayers.json from the live OSRS wiki.

Spells come from {{Infobox Spell}} (~226 pages) and carry their rune cost as a
nested {{RuneReq|Nature=1|Fire=5}} template, which is resolved to item ids so a
consumer can check an inventory directly. Prayers come from {{Infobox Prayer}}
(~57), and {{Prayer info}} (~193) covers bone-burying XP on item pages.

Usage:
    python3 tools/build_osrs_magic.py --out data/wiki
"""

import argparse
import json
import re
import time
from pathlib import Path

from build_osrs_items import item_lookup
from osrs_wiki import (
    wiki_title,
    fetch_wikitext, find_templates, load_dataset, log, pages_embedding,
    progress_bar, strip_markup, template_params, wiki_bool, wiki_file,
    wiki_int, wiki_num,
)


def parse_rune_cost(raw, lookup=None):
    """{{RuneReq|Nature=1|Fire=5}} -> [{name, id, quantity}].

    The template names runes bare ("Nature"), while the item is "Nature rune",
    so the suffix is added before lookup. Combination runes and staves are
    listed the same way.
    """
    out = []
    for block in find_templates(raw or "", "RuneReq"):
        for rune, qty in template_params(block).items():
            name = rune.strip().capitalize()
            if not name:
                continue
            entry = {"rune": name, "quantity": wiki_int(qty, 1) or 1}
            if lookup:
                # "Nature" -> "Nature rune"; some entries already say "rune"
                iid = lookup(name if name.lower().endswith("rune")
                             else f"{name} rune") or lookup(name)
                if iid is not None:
                    entry["id"] = iid
            out.append(entry)
    return out


def parse_spells(texts, lookup=None):
    rows = []
    for title, text in texts.items():
        for block in find_templates(text, "Infobox Spell"):
            p = template_params(block)
            rows.append({
                "name": strip_markup(p.get("name") or title),
                "page": title,
                "isMembers": wiki_bool(p.get("members")),
                "level": wiki_int(p.get("level")),
                "spellbook": wiki_title(strip_markup(p.get("spellbook", ""))),
                "type": wiki_title(strip_markup(p.get("type", ""))),
                "experience": wiki_num(p.get("exp"), None),
                # "5 ticks" -> 5
                "ticks": wiki_int(p.get("speed")),
                "maxHit": strip_markup(p.get("damage", "")) or None,
                "element": strip_markup(p.get("element", "")) or None,
                "description": strip_markup(p.get("description", "")) or None,
                "quest": strip_markup(p.get("quest", "")) or None,
                "cooldown": strip_markup(p.get("cooldown", "")) or None,
                "lectern": strip_markup(p.get("lectern", "")) or None,
                "slayerLevel": wiki_int(p.get("slayerlevel")),
                "removal": strip_markup(p.get("removal", "")) or None,
                "removalUpdate": strip_markup(p.get("removalupdate", "")) or None,
                "runes": parse_rune_cost(p.get("cost"), lookup),
                "image": wiki_file(p.get("image")),
                "release": strip_markup(p.get("release", "")) or None,
            })
    return rows


def parse_prayers(texts):
    rows = []
    for title, text in texts.items():
        for block in find_templates(text, "Infobox Prayer"):
            p = template_params(block)
            rows.append({
                "name": strip_markup(p.get("name") or title),
                "page": title,
                "isMembers": wiki_bool(p.get("members")),
                "level": wiki_int(p.get("level")),
                "drain": wiki_int(p.get("drain")),
                "effect": strip_markup(p.get("effect", "")) or None,
                "image": wiki_file(p.get("image")),
                "release": strip_markup(p.get("release", "")) or None,
            })
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki", help="output directory")
    ap.add_argument("--items", help="all-items.json path "
                                    "(default: <out>/all-items.json)")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    lookup = None
    items_path = Path(args.items) if args.items else out / "all-items.json"
    if items_path.exists():
        lookup = item_lookup(load_dataset(items_path))
        log("loaded items for rune lookup")

    t0 = time.time()
    titles = pages_embedding("Template:Infobox Spell")
    log(f"spell pages: {len(titles)}")
    spells = parse_spells(fetch_wikitext(titles, progress=progress_bar("spells")),
                          lookup)
    spells.sort(key=lambda r: (r["spellbook"] or "", r["level"] or 0, r["name"]))
    (out / "all-spells.json").write_text(json.dumps(spells, indent=1))
    runed = sum(1 for s in spells if s["runes"])
    unresolved = sum(1 for s in spells for r in s["runes"] if "id" not in r)
    log(f"wrote {out / 'all-spells.json'}  ({len(spells)} spells, "
        f"{time.time() - t0:.0f}s)")
    log(f"  {runed} with a rune cost ({unresolved} unresolved rune names)")

    t0 = time.time()
    titles = pages_embedding("Template:Infobox Prayer")
    log(f"prayer pages: {len(titles)}")
    prayers = parse_prayers(fetch_wikitext(titles, progress=progress_bar("prayers")))
    prayers.sort(key=lambda r: (r["level"] or 0, r["name"]))
    (out / "all-prayers.json").write_text(json.dumps(prayers, indent=1))
    log(f"wrote {out / 'all-prayers.json'}  ({len(prayers)} prayers, "
        f"{time.time() - t0:.0f}s)")


if __name__ == "__main__":
    main()
