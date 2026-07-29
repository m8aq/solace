#!/usr/bin/env python3
"""
Find wiki templates carrying data the extractors don't read yet.

audit_wiki_fields.py checks coverage *within* templates we already parse. This
checks the layer above: which templates appear on our source pages at all. That
gap is how {{LocLine}} was missed -- named dungeon locations live in the page
body, not the infobox, so no amount of infobox auditing would have surfaced it.

Run after any wiki content shift, or when a dataset looks thinner than expected.
Anything with meaningful page reach that isn't in PARSED or NOISE deserves a
look before being dismissed.

Usage:
    python3 tools/sweep_wiki_templates.py
    python3 tools/sweep_wiki_templates.py --sample 400 --min-pages 20
"""

import argparse
import collections
import re

from osrs_wiki import fetch_wikitext, log, pages_embedding, progress_bar

# Templates the build_osrs_* scripts already extract.
PARSED = {
    "infobox item", "infobox bonuses", "combatstyles", "recipe",
    "infobox npc", "infobox monster", "map", "npc map", "locline",
    "dropsline", "dropslineclue", "dropslineskill", "dropslinereward",
    "raredroptable", "herbdroplines", "gemdroptable",
    "wildernessslayerdroptable", "uncommonseeddroplines", "rareseeddroplines",
    "catacombsdroptable", "talismandroplines",
    "infobox scenery", "objectlocline", "infobox location", "infobox spell",
    "infobox prayer", "infobox quest", "quest details", "quest rewards",
    "runereq", "relativelocation", "scp",
    "farming info", "woodcutting info", "mining info", "fishing info",
    "hunter info", "thieving info", "agility info",
    "infobox shop", "storetablehead", "storeline",
    "itemspawnline", "infobox construction",
}

# Presentation, navigation and citation markup — no facts of their own.
NOISE = re.compile(
    r"^(cite\w*|refn?|reflist|namedref|main|otheruse|for|see ?also|"
    r"hastranscript|has ?(calculator|skill guide|task|strategy)|dead ?link|"
    r"hidden|nowrap|clear|float\w*|align|center|left|right|small|big|sic|"
    r"subject[ _]?changes?|update|external|stub|disambig|redirect|dab|listen|"
    r"audio|coins|nocoins|plink\w*|scp|skillclickpic|na|yes|no|members|chat|"
    r"dialogue|quest\w*|drops?table(head|bottom)|loctable(head|bottom)|"
    r"storetable(head|bottom)|itemspawntable(head|bottom)|emoteclue\w*|infotable|colou?r|bracket|tooltip|"
    r"abbr|anchor|note|efn|sup|sub|multi ?infobox|switch ?infobox|"
    r"synced? ?switch|sync|defver|navbox|.*navbox|.*footer|.*header|series|"
    r"mmgsection|average drop value|drop ?log ?project|floornumber|fairycode|"
    r"wp|mes|gep|tabber|brimstone rarity|herbdroptableinfo|instructions|"
    r"uses material list|drop sources|store locations list|"
    r"used in recommended equipment|skilling success chart|"
    r"combat achievements list|costume storage|prayer info|otheruses|"
    r"uncommonseeddroptableinfo|rareseeddroptableinfo|overhead|confuse|"
    r"price per dose|uses facility list|thieving info|pickpocket|"
    r"crypticnpc|anagram|npc contact|"
    # equipment/monster category navboxes
    r"(bronze|iron|steel|black|mithril|adamant|rune|dragon) equipment|"
    r"(slash|stab|crush|ranged|magic|melee) weapons|"
    r"(ranged|magic|melee) armour|melee headgear|bolts|boots|cape|"
    r"slayer monsters|demons|bosses|dragon|god wars dungeon|"
    r"fletching|potions|.* clue rewards|.*(rewards|equipment|weapons)|"
    r"varrock|east ardougne|rellekka|lunar diplomacy|song of the elves)$", re.I)

SOURCES = [("Infobox Monster", "monsters"), ("Infobox NPC", "npcs"),
           ("Recipe", "recipes"), ("Infobox Item", "items")]


def sweep(template_page, label, sample):
    titles = pages_embedding(f"Template:{template_page}")
    if sample and sample < len(titles):
        titles = titles[:sample]
    log(f"{label}: {len(titles)} pages")
    texts = fetch_wikitext(titles, progress=progress_bar(label))

    uses, reach = collections.Counter(), collections.Counter()
    for text in texts.values():
        seen = set()
        for m in re.finditer(r"\{\{\s*([A-Za-z][\w '\-/()]*?)\s*(?=[|}\n])", text):
            name = re.sub(r"\s+", " ", m.group(1)).strip()
            uses[name] += 1
            seen.add(name.lower())
        for s in seen:
            reach[s] += 1
    return uses, reach, len(texts)


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--sample", type=int, default=1200,
                    help="pages to scan per source (default 1200, 0 for all)")
    ap.add_argument("--min-pages", type=int, default=15,
                    help="only report templates reaching this many pages")
    args = ap.parse_args()

    findings = []
    for tpl, label in SOURCES:
        uses, reach, npages = sweep(tpl, label, args.sample or None)
        rows = []
        for name, n in uses.items():
            low = name.lower()
            if low in PARSED or NOISE.match(low) or reach[low] < args.min_pages:
                continue
            rows.append((reach[low], n, name))
        rows.sort(reverse=True)
        # collapse case variants of the same template name
        seen = set()
        deduped = []
        for pages, n, name in rows:
            if name.lower() in seen:
                continue
            seen.add(name.lower())
            deduped.append((pages, n, name))

        print(f"\n=== {label}: {npages} pages — unparsed templates ===")
        if not deduped:
            print("  (nothing above threshold)")
        for pages, n, name in deduped[:20]:
            print(f"  {pages:5} pages ({100 * pages // max(npages, 1):3}%)  "
                  f"{n:6} uses   {name}")
        findings += deduped

    print(f"\n{len(findings)} candidate(s) above {args.min_pages} pages. "
          f"Add real ones to the extractors, noise to NOISE.")


if __name__ == "__main__":
    main()
