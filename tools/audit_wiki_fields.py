#!/usr/bin/env python3
"""
Report which template parameters the build_osrs_* extractors actually consume.

Censuses every parameter occurrence of a template across all pages that use it,
then diffs against the keys the matching extractor reads. Anything unconsumed
is either genuinely missing data or wiki plumbing worth ignoring deliberately.

This catches real bugs: it is how we found the Recipe template spells it
`facilities` (not `facility`), that `tools` is comma-separated, and that
`ticks = 0` was being nulled by a truthiness check.

Numbered variants are folded together, so `id1`/`id2` both count as `idN` and
are treated as consumed when the extractor reads `id` through the versioned
fallback helper.

Usage:
    python3 tools/audit_wiki_fields.py                 # all templates
    python3 tools/audit_wiki_fields.py "Infobox NPC"
    python3 tools/audit_wiki_fields.py Recipe --sample 300
"""

import argparse
import collections
import random
import re
import sys

from osrs_wiki import (
    fetch_wikitext, find_templates, log, pages_embedding, progress_bar,
    template_params,
)

# Params that exist to drive wiki rendering or categorisation rather than to
# state a fact. Skipping these is deliberate, so they are excluded from the
# coverage ratio instead of counting against it.
IGNORED = {
    "gemw",          # whether to render a live GE price
    "image", "icon", "zoom", "title", "align", "width", "height",
    "bucket", "bucketname",   # wiki-side categorisation buckets
    "anim", "sound",  # animation / sound-effect media
    "defver",        # pointer to the default variant
    "dpscalc",       # DPS-calculator plumbing
    "altraritydash", "alt",
    "rectx", "recty", "group", "place",
    "versions", "dropverison",   # typo'd variants of handled keys
    # shop table column/visibility switches
    "hidestock", "hiderestock", "hidege", "hidebuy", "hidesell",
    "hidecaption", "column", "gemwname", "namenotes",
}

DROP_KEYS = {
    "name", "quantity", "rarity", "altrarity", "rolls", "approx",
    "raritynotes", "namenotes", "quantitynotes", "dropversion", "skill",
    "leagueregion",
}

# Keys each extractor reads. A bare key also covers its `keyN` variants.
CONSUMED = {
    "Infobox Item": {
        "id", "name", "version", "examine", "members", "value", "weight",
        "quest", "stackable", "tradeable", "equipable", "noteable", "alchable",
        "bankable", "edible", "placeholder", "exchange", "stacksinbank",
        "options", "wornoptions", "respawn", "buylimit", "gemwname", "image",
        "release", "update", "destroy", "leagueregion", "removal",
        "removalupdate", "aka",
    },
    "Recipe": {
        "matN", "matNquantity", "matNcost", "matNitemnote", "matNtxt",
        "matNsubtxt", "matNsubtext", "matNquantitynote", "matNcostnote",
        "matNqty", "matNnum",
        "outputN", "outputNquantity", "outputNcost", "outputNitemnote",
        "outputNtxt", "outputNsubtxt", "outputNsubtext", "outputNquantitynote",
        "outputNcostnote", "outputNqty", "outputNnum",
        "skillN", "skillNlvl", "skillNexp", "skillNboostable",
        "skill", "skilllvl", "skillexp", "skillboostable",
        "members", "ticks", "ticksnote", "notes", "note", "facilities",
        "facility", "tools", "tool", "name",
    },
    "DropsLine": DROP_KEYS,
    "DropsLineSkill": DROP_KEYS,
    "DropsLineReward": DROP_KEYS,
    "CombatStyles": {"speed", "attackrange"},
    "StoreTableHead": {
        "sellmultiplier", "buymultiplier", "delta", "currency", "members",
        "location", "shopversion",
    },
    "StoreLine": {
        "name", "stock", "restock", "buy", "sell", "displayname", "bucketname",
    },
    "Infobox Shop": {
        "name", "location", "owner", "special", "members", "map",
        "leagueregion", "release", "update", "version",
    },
    "ItemSpawnLine": {
        "name", "location", "members", "leagueregion", "mapid", "plane",
    },
    "Infobox Construction": {
        "name", "version", "furniturename", "level", "experience", "room",
        "hotspot", "flatpack", "itemid", "id", "options", "examine",
        "release", "update", "leagueregion",
    },
    "Infobox Scenery": {
        "id", "name", "version", "examine", "members", "options", "location",
        "quest", "leagueregion", "map", "release", "update",
    },
    "ObjectLocLine": {
        "name", "location", "levels", "members", "plane", "mapid", "mtype",
        "r", "leagueregion", "version", "dropversion", "spawns",
    },
    "Farming info": {
        "name", "version", "level", "patch", "seed", "seedsper", "payment",
        "time", "plant", "crop", "yield", "regrow", "plantxp", "checkxp",
        "harvestxp", "seedling", "sapling", "skillNname", "skillNlvl", "skillNexp", "skillNnote",
        "dropversion",
    },
    "Woodcutting info": {"skillNname", "skillNlvl", "skillNexp", "skillNnote", "dropversion", "type", "typename", "timename", "location", "time", "name", "version", "level", "xp", "tool", "time", "tree"},
    "Mining info": {"skillNname", "skillNlvl", "skillNexp", "skillNnote", "dropversion", "type", "typename", "timename", "location", "time", "name", "version", "level", "xp", "tool", "time", "rock"},
    "Fishing info": {"skillNname", "skillNlvl", "skillNexp", "skillNnote", "dropversion", "type", "typename", "timename", "location", "time", "name", "version", "level", "xp", "bait", "tool", "spot"},
    "Hunter info": {"skillNname", "skillNlvl", "skillNexp", "skillNnote", "dropversion", "type", "typename", "timename", "location", "time", "name", "version", "level", "xp", "trap", "retaliation",
                    "container", "wildxp", "bait", "facility"},
    "Agility info": {"name", "version", "level", "xp", "type", "course",
                     "failxp", "skillNname", "skillNlvl", "skillNexp",
                     "skillNnote"},
    "Relativelocation": {"location", "north", "south", "east", "west"},
    "Thieving info": {"skillNname", "skillNlvl", "skillNexp", "skillNnote", "dropversion", "type", "typename", "timename", "location", "time", "name", "version", "level", "xp", "type", "tool",
                      "damage", "time", "timename"},
    "Infobox Spell": {
        "name", "members", "level", "spellbook", "type", "exp", "cost",
        "speed", "description", "damage", "element", "image", "release",
        "update", "quest", "cooldown", "lectern", "slayerlevel", "removal",
        "removalupdate",
    },
    "Infobox Prayer": {
        "name", "members", "level", "drain", "effect", "image", "release",
        "update",
    },
    "Quest details": {
        "start", "startmap", "difficulty", "length", "description", "items",
        "recommended", "requirements", "kills", "ironman", "members",
        "leagueregion",
    },
    "Quest rewards": {"name", "qp", "rewards", "image"},
    "Infobox Location": {
        "name", "members", "map", "leagueregion", "location", "type", "music",
        "race", "teleport", "aka", "floors", "image", "release", "update",
        "requirement", "wilderness", "capital", "tellers", "depositbox",
        "pollbooth", "removal", "version",
    },
    "LocLine": {
        "name", "location", "levels", "members", "plane", "mapid", "mtype",
        "r", "leagueregion", "version", "dropversion", "spawns",
    },
    "Infobox NPC": {
        "id", "name", "version", "examine", "members", "race", "gender",
        "location", "quest", "level", "size", "respawn", "shop", "options",
        "image", "map",
        "release", "update", "removal", "removalupdate", "leagueregion", "aka",
    },
    "Infobox Monster": {
        "id", "name", "version", "examine", "members", "aggressive",
        "poisonous", "attack style", "max hit", "attributes", "cat",
        "assignedby", "elementalweaknesstype", "elementalweaknesspercent",
        "image", "map", "release", "update", "removal", "removalupdate",
        "leagueregion", "aka", "scaledhp",
        "combat", "hitpoints", "size", "att", "str", "def", "mage", "range",
        "attbns", "strbns", "amagic", "mbns", "arange", "rngbns",
        "dstab", "dslash", "dcrush", "dmagic", "dlight", "dstandard", "dheavy",
        "flatarmour", "xpbonus", "attack speed", "respawn", "slaylvl",
        "slayxp", "poisonresistance", "venomresistance", "freezeresistance",
        "immunepoison", "immunevenom", "immunecannon", "immunethrall",
        "immuneburn",
    },
}


def normalise(key):
    """`id1` -> `idN` so numbered variants collapse to one bucket."""
    return re.sub(r"\d+", "N", key)


def expand(consumed):
    """A consumed bare key also covers its versioned `keyN` form."""
    return consumed | {k + "N" for k in consumed}


def audit(template, sample=None):
    titles = pages_embedding(f"Template:{template}")
    if sample and sample < len(titles):
        random.seed(0)
        titles = random.sample(titles, sample)
    log(f"{template}: {len(titles)} pages")
    texts = fetch_wikitext(titles, progress=progress_bar(template.lower()))

    keys = collections.Counter()
    for text in texts.values():
        for block in find_templates(text, template):
            for k in template_params(block):
                keys[normalise(k)] += 1

    consumed = expand(CONSUMED.get(template, set()))
    ignored = expand(IGNORED)
    hit = sum(v for k, v in keys.items() if k in consumed)
    skipped = sum(v for k, v in keys.items()
                  if k not in consumed and k in ignored)
    missed = {k: v for k, v in keys.items()
              if k not in consumed and k not in ignored}
    # coverage is measured against params that actually state a fact
    data_total = hit + sum(missed.values())

    print(f"\n=== {template} — {len(texts)} pages, {len(keys)} distinct params ===")
    print(f"consumed {hit}/{data_total} data params "
          f"({100 * hit // max(data_total, 1)}%), {skipped} render-only skipped")
    if missed:
        print(f"\n  unconsumed ({len(missed)} params):")
        for k, v in sorted(missed.items(), key=lambda kv: -kv[1])[:25]:
            print(f"    {v:6}  {k}")
    return hit, data_total


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("template", nargs="?", choices=sorted(CONSUMED),
                    help="template to audit (default: all)")
    ap.add_argument("--sample", type=int,
                    help="audit a random subset of pages instead of all")
    args = ap.parse_args()

    targets = [args.template] if args.template else sorted(CONSUMED)
    results = [(t, *audit(t, args.sample)) for t in targets]

    print("\n=== summary ===")
    for name, hit, total in results:
        print(f"  {name:18} {100 * hit // max(total, 1):3}%  ({hit}/{total})")
    if any(100 * h // max(t, 1) < 97 for _, h, t in results):
        sys.exit(1)


if __name__ == "__main__":
    main()
