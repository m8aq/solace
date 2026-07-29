#!/usr/bin/env python3
"""
Shared OSRS wiki client and wikitext parser.

Used by the build_osrs_*.py extractors. Stdlib only, no external dependencies.

The wiki's structured data lives in flat templates, e.g.

    {{Infobox Item|name = Snape grass|members = Yes|value = 10|id = 231}}

so the parsing model here is: find brace-balanced template blocks by name,
split their named parameters, and coerce the values. Rendered HTML is a much
worse source for the same data (templates expand into nested markup), which is
why everything goes through action=parse&prop=wikitext instead.
"""

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://oldschool.runescape.wiki/api.php"
UA = "solace-osrs-data/0.1 (https://github.com/m8aq/solace)"
BATCH = 50  # wiki API cap for content queries


# ---------------------------------------------------------------- wiki client

class WikiError(RuntimeError):
    """An API-level failure: HTTP 200 with an `error` object in the body."""

    def __init__(self, code, info):
        super().__init__(f"{code}: {info}")
        self.code = code


# Errors that mean "later", not "no". `ratelimited` is the one that matters:
# the API signals throttling in a 200 response, so without this a hammered
# build reads it as data and quietly returns nothing.
TRANSIENT = {"ratelimited", "maxlag", "readonly", "internal_api_error",
             "internal_api_error_DBQueryError", "backend-fail-internal"}


def api_get(params, retries=4):
    """One API call, with exponential backoff on transport and API errors.

    MediaWiki reports most failures as HTTP 200 with an `error` object rather
    than a status code, so returning the parsed body unchecked hands callers a
    dict with no `query` in it. The ones that use `.get("query", {})` then see
    an empty result and carry on -- which is how a throttled run loses whole
    batches without anything appearing to go wrong.
    """
    params.setdefault("format", "json")
    params.setdefault("formatversion", "2")
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                payload = json.load(r)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt == retries - 1:
                raise
            time.sleep(2 ** attempt)
            continue
        # opensearch answers with a bare JSON array, so there is nothing to
        # look an error up in; only a mapping can carry one.
        if not isinstance(payload, dict):
            return payload
        error = payload.get("error")
        if not error:
            return payload
        # usually {"code":..., "info":...}, but some modules return a bare
        # string, and treating that as a dict turns a wiki error into an
        # AttributeError from inside the retry loop
        if isinstance(error, dict):
            code, info = error.get("code", ""), error.get("info", "")
        else:
            code, info = "", str(error)
        if code not in TRANSIENT or attempt == retries - 1:
            raise WikiError(code, info)
        time.sleep(2 ** attempt)


def pages_embedding(template, namespace=0):
    """Every mainspace page transcluding `template`."""
    out, cont = [], {}
    while True:
        d = api_get({"action": "query", "list": "embeddedin", "eititle": template,
                     "eilimit": 500, "einamespace": namespace, **cont})
        out += [p["title"] for p in d["query"]["embeddedin"]]
        if "continue" not in d:
            return out
        cont = d["continue"]


def fetch_wikitext(titles, progress=None):
    """{title: wikitext} for many titles, batched."""
    out = {}
    for i in range(0, len(titles), BATCH):
        chunk = titles[i:i + BATCH]
        d = api_get({"action": "query", "titles": "|".join(chunk),
                     "prop": "revisions", "rvprop": "content", "rvslots": "main"})
        for p in d.get("query", {}).get("pages", []):
            try:
                out[p["title"]] = p["revisions"][0]["slots"]["main"]["content"]
            except (KeyError, IndexError):
                continue
        if progress:
            progress(min(i + BATCH, len(titles)), len(titles))
        time.sleep(0.1)
    return out


def fetch_redirects(titles, progress=None):
    """{target title: [redirecting titles]} — misspellings and alternate names."""
    out = {}
    for i in range(0, len(titles), BATCH):
        chunk = titles[i:i + BATCH]
        cont = {}
        while True:
            d = api_get({"action": "query", "titles": "|".join(chunk),
                         "prop": "redirects", "rdlimit": "max", "rdnamespace": 0,
                         **cont})
            for p in d.get("query", {}).get("pages", []):
                out.setdefault(p["title"], []).extend(
                    r["title"] for r in p.get("redirects", []))
            if "continue" not in d:
                break
            cont = d["continue"]
        if progress:
            progress(min(i + BATCH, len(titles)), len(titles))
        time.sleep(0.1)
    return out


# ------------------------------------------------------------ wikitext parsing

def split_top_level(s, sep="|"):
    """Split on `sep` at brace/bracket depth 0, so nested templates stay intact."""
    parts, depth, cur, i = [], 0, "", 0
    while i < len(s):
        two = s[i:i + 2]
        if two in ("{{", "[[", "{|"):
            depth += 1; cur += two; i += 2; continue
        if two in ("}}", "]]", "|}"):
            depth -= 1; cur += two; i += 2; continue
        if s[i] == sep and depth == 0:
            parts.append(cur); cur = ""; i += 1; continue
        cur += s[i]; i += 1
    parts.append(cur)
    return parts


def _block_at(text, start):
    """The brace-balanced `{{...}}` block beginning at `start`, or None."""
    depth, i = 0, start
    while i < len(text):
        if text[i:i + 2] == "{{":
            depth += 1; i += 2; continue
        if text[i:i + 2] == "}}":
            depth -= 1; i += 2
            if depth == 0:
                return text[start:i]
            continue
        i += 1
    return None


def find_templates(text, name):
    """All `{{name|...}}` blocks, brace-balanced, case-insensitive."""
    out = []
    pat = re.compile(r"\{\{\s*" + re.escape(name) + r"\s*(?=[|}\n])", re.I)
    for m in pat.finditer(text):
        block = _block_at(text, m.start())
        if block is not None:
            out.append(block)
    return out


def iter_templates(text, names):
    """Yield (name, block) for several templates in document order.

    find_templates handles one name at a time and so loses the interleaving
    between them. Some structures are positional rather than nested -- a shop
    is a {{StoreTableHead}} followed by its {{StoreLine}} rows, and a page may
    hold several -- so rows can only be bound to the right owner by walking the
    page in order.

    The yielded name is the caller's spelling from `names`, not the page's.
    """
    pat = re.compile(
        r"\{\{\s*(" + "|".join(re.escape(n) for n in names) + r")\s*(?=[|}\n])",
        re.I)
    canonical = {n.lower(): n for n in names}
    for m in pat.finditer(text):
        block = _block_at(text, m.start())
        if block is not None:
            yield canonical[m.group(1).strip().lower()], block


def template_params(block):
    """`{{X|a=1|b=2}}` -> {'a': '1', 'b': '2'} (named params only, keys lowercased)."""
    params = {}
    for part in split_top_level(block[2:-2])[1:]:
        if "=" not in part:
            continue
        k, v = part.split("=", 1)
        params[k.strip().lower()] = v.strip()
    return params


def template_positionals(block):
    """`{{X|a=1|foo|bar}}` -> ['foo', 'bar'] (unnamed params, in order)."""
    out = []
    for part in split_top_level(block[2:-2])[1:]:
        if re.match(r"^\s*[\w-]+\s*=", part):
            continue
        part = part.strip()
        if part:
            out.append(part)
    return out


def versioned(params, idx):
    """
    Resolver for versioned infoboxes.

    Pages describing variants suffix their keys (`name1`, `examine2`, ...) and
    fall back to the unsuffixed key when a variant doesn't override it. Returns
    a `v(key, default)` callable bound to one variant index.
    """
    def v(key, default=None):
        return params.get(f"{key}{idx}", params.get(key, default))
    return v


def strip_markup(s):
    """Wikitext -> plain text: drop refs, unwrap links, remove templates/HTML."""
    s = re.sub(r"<ref.*?(?:/>|</ref>)", "", s, flags=re.S | re.I)
    # An embedded image is not text. Unwrapping it leaks either the filename or
    # a size ("25px") into prose, and with no pipe it welds to whatever follows:
    # "[[File:Arceuus.png]]Arceuus spellbook" -> "File:Arceuus.pngArceuus...".
    s = re.sub(r"\[\[\s*(?:File|Image)\s*:[^\]]*\]\]", " ", s, flags=re.I)
    s = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", s)
    # Templates nest ({{Refn|...{{SCP|...}}...}}), and one pass only clears the
    # innermost, leaving the outer shell's text behind. Repeat to a fixed point.
    while True:
        stripped = re.sub(r"\{\{[^{}]*\}\}", "", s)
        if stripped == s:
            break
        s = stripped
    # Line breaks separate values ("12 minutes<br/>6 minutes in the Guild").
    # Deleting the tag outright welds them into "12 minutes6 minutes".
    s = re.sub(r"<\s*(br|/?p|/?li|/?tr|/?div)\b[^>]*>", " ", s, flags=re.I)
    s = re.sub(r"'''?|<[^>]+>", "", s)
    return re.sub(r"\s+", " ", s).strip()


def wiki_bool(v):
    return str(v).strip().lower() in ("yes", "true", "1")


def wiki_num(v, default=0):
    if v is None:
        return default
    m = re.search(r"-?[\d,]*\.?\d+", str(v).replace(",", ""))
    return float(m.group()) if m else default


def wiki_int(v, default=None):
    """Integer if the value contains a digit, else `default`.

    Distinct from `int(wiki_num(...))`: a real 0 stays 0 rather than collapsing
    into the falsy default.
    """
    if v is None or not re.search(r"\d", str(v)):
        return default
    return int(wiki_num(v))


_COMMENT = re.compile(r"<!--.*?-->", re.S)
# `hist`/`beta` mark content that is not in the live game. `override` marks a
# live id that replaces another, so it is kept.
_DEAD_ID = re.compile(r"\b(hist|beta)\d+", re.I)


def parse_ids(raw):
    """An `id` param -> [int], ignoring anything that is not a live game id.

    Editors annotate these fields heavily and the digits inside those notes are
    not ids:

        id3 = 110<!--Also unused IDs 116, 117-->   ->  [110], not [110,116,117]
        id  = hist11249                            ->  []   (removed content)
        id1 = 10529,29103,29104 <!-- standard -->  ->  [10529, 29103, 29104]

    Reading every digit fabricated ids that collided with real ones -- a `hist1`
    on a removed April Fools NPC produced id 1, which is a live monster.
    """
    if not raw:
        return []
    cleaned = _DEAD_ID.sub(" ", _COMMENT.sub(" ", str(raw)))
    return [int(n) for n in re.findall(r"\d+", cleaned)]


def wiki_plink(v):
    """Text of a value that may be a `{{plink|Item}}` item link.

    strip_markup deletes templates outright, which silently emptied fields like
    `seed = {{plink|Snape grass seed}}` -- exactly the ones that carry the item
    name. Unwrap plink-style links first, keeping any quantity prefix.
    """
    if not v:
        return None
    s = str(v)
    # {{plink|Name}} / {{plinkt|Name|pic=...}} / {{plink|Name|txt=Other}}
    s = re.sub(r"\{\{\s*plink[a-z]*\s*\|\s*([^|}]+)(?:\|[^}]*)?\}\}",
               r"\1", s, flags=re.I)
    return strip_markup(s) or None


def wiki_file(v):
    """`[[File:Shark.png|150px]]` -> `Shark.png`.

    strip_markup would return the last link segment ("150px"), so image params
    need their own extractor.
    """
    if not v:
        return None
    m = re.search(r"\[\[\s*(?:File|Image)\s*:\s*([^\]|]+)", str(v), re.I)
    if m:
        return m.group(1).strip()
    s = strip_markup(v)
    return s or None


# Values may be joined by a comma or an ampersand ("Karamja&Kandarin").
_CATEGORY_SPLIT = re.compile(r"\s*([,&])\s*")


def _title_word(word):
    """Capitalise a word unless it already carries an internal capital.

    Protects names the game spells its own way -- TzHaar, McGrubor -- while
    still fixing `kandarin` and `slash sword`.
    """
    if not word or any(c.isupper() for c in word[1:]):
        return word
    return word[0].upper() + word[1:]


def wiki_title(v):
    """Normalise a categorical value's capitalisation.

    Editors write `Kandarin` and `kandarin`, `slash sword` and `Slash Sword`,
    splitting what should be one bucket in two -- `leagueRegion` alone was
    split across thousands of records. Every word is normalised, not just the
    first, because the variants differ on any of them.
    """
    if not v:
        return None
    out = []
    for token in _CATEGORY_SPLIT.split(str(v).strip()):
        if token in (",", "&"):
            out.append(token if token == "&" else ", ")
            continue
        out.append(" ".join(_title_word(w) for w in token.split()))
    return "".join(out).strip().strip(",").strip() or None


# Farming patch names are written both with and without the redundant suffix:
# `Allotment` (84) and `Allotment patch` (80) are the same patch.
_PATCH_SUFFIX = re.compile(r"\s+patch(es)?$", re.I)


def wiki_patch(v):
    """A farming patch name: markup stripped, no redundant ' patch' suffix."""
    name = wiki_title(strip_markup(str(v))) if v else None
    return _PATCH_SUFFIX.sub("", name).strip() or None if name else None


def wiki_list(v, sep=",", title=False):
    """Comma-separated template value -> list, dropping blanks and N/A.

    `title=True` normalises capitalisation for categorical lists such as
    `attributes` and `assignedBy`, where editors mix `undead` and `Undead`.
    """
    if not v:
        return []
    out = [x for x in (strip_markup(p) for p in split_top_level(v, sep))
           if x and x.lower() not in ("n/a", "none")]
    return [x[0].upper() + x[1:] for x in out] if title else out


# --------------------------------------------------------------- coordinates

# {{Map}} writes bare pairs ("1843,3728"); {{LocLine}} labels them
# ("x:2897,y:9797"). Both appear as unnamed template arguments.
_COORD = re.compile(r"^\s*(?:x\s*:\s*)?(\d{2,5})\s*,\s*(?:y\s*:\s*)?(\d{2,5})\s*$", re.I)


def coord_args(block):
    """Unnamed template arguments that are coordinate pairs -> [(x, y)]."""
    out = []
    for arg in template_positionals(block):
        m = _COORD.match(arg)
        if m:
            out.append((int(m.group(1)), int(m.group(2))))
    return out


def _point(x, y, p, mtype, radius):
    pt = {"x": x, "y": y, "plane": wiki_int(p.get("plane"), 0) or 0}
    map_id = wiki_int(p.get("mapid"))
    if map_id:
        pt["mapID"] = map_id
    if mtype:
        pt["mtype"] = mtype
    if radius and mtype in ("square", "circle", "rect"):
        pt["radius"] = radius
    return pt


# {{NPC map}} is a redirect to {{Map}} with identical parameters, so both
# spellings appear in the wild and both must be matched.
MAP_TEMPLATES = ("Map", "NPC map")


def parse_map_features(raw):
    """`map` infobox param -> [{x, y, plane, mapID, mtype}], deduplicated.

    A {{Map}} block carries points either as a named x=/y= pair or as unnamed
    coordinate arguments, and often both. Each point inherits its own block's
    plane and mapID, so upper floors and instances stay distinguishable.
    """
    if not raw:
        return []
    blocks = [b for name in MAP_TEMPLATES for b in find_templates(raw, name)]
    spawns, seen = [], set()
    for block in blocks:
        p = template_params(block)
        mtype = strip_markup(p.get("mtype", "")).lower() or None
        radius = wiki_int(p.get("r"))

        points = list(coord_args(block))
        x, y = wiki_int(p.get("x")), wiki_int(p.get("y"))
        if x is not None and y is not None:
            points.insert(0, (x, y))

        for x, y in points:
            pt = _point(x, y, p, mtype, radius)
            key = (pt["x"], pt["y"], pt["plane"], pt.get("mapID"))
            if key in seen:
                continue
            seen.add(key)
            spawns.append(pt)
    return spawns


def parse_loc_lines(text, template="LocLine"):
    """{{LocLine}} blocks -> [{location, levels, plane, spawns: [...]}].

    Infobox `map` params only cover a page's headline location. The body's
    Locations table is where named places live -- "Taverley Dungeon",
    "Corsair Cove Dungeon" -- each with its own plane and coordinate list.
    This is the only source that names a dungeon.

    Scenery pages use {{ObjectLocLine}} with the same field layout, so pass
    `template="ObjectLocLine"` for rocks, trees and farming patches.
    """
    out = []
    for block in find_templates(text, template):
        p = template_params(block)
        location = strip_markup(p.get("location", ""))
        points = coord_args(block)
        if not location and not points:
            continue
        mtype = strip_markup(p.get("mtype", "")).lower() or None
        radius = wiki_int(p.get("r"))
        out.append({
            "location": location or None,
            "levels": strip_markup(p.get("levels", "")) or None,
            "isMembers": wiki_bool(p.get("members")),
            "leagueRegion": wiki_title(strip_markup(p.get("leagueregion", ""))),
            # which infobox variant lives here, and which drop table it uses
            "version": strip_markup(p.get("version", "")) or None,
            "dropVersion": strip_markup(p.get("dropversion", "")) or None,
            # editor-stated spawn count, which can exceed the plotted points
            "spawnCount": wiki_int(p.get("spawns")),
            "spawns": [_point(x, y, p, mtype, radius) for x, y in points],
        })
    return out


# Ground spawns annotate their coordinates: "2770,4516,qty:3" or
# "2770,4516,qty:1-3,respawn:25". coord_args deliberately only matches bare
# pairs, so annotated points need their own pattern.
_SPAWN_COORD = re.compile(
    r"^\s*(\d{2,5})\s*,\s*(\d{2,5})"
    r"(?:\s*,\s*qty\s*:\s*(\d+(?:\s*-\s*\d+)?))?"
    r"(?:\s*,\s*respawn\s*:\s*(\d+))?\s*$", re.I)


def parse_item_spawns(text):
    """{{ItemSpawnLine}} -> [{name, location, spawns: [...]}].

    One row per named location, each holding every ground-spawn tile there.
    Points carry the same x/y/plane/mapID shape as NPC and monster spawns, plus
    an optional quantity and respawn timer.
    """
    out = []
    for block in find_templates(text, "ItemSpawnLine"):
        p = template_params(block)
        name = strip_markup(p.get("name", ""))
        points = []
        for arg in template_positionals(block):
            m = _SPAWN_COORD.match(arg)
            if not m:
                continue
            pt = _point(int(m.group(1)), int(m.group(2)), p, None, None)
            if m.group(3):
                qty = m.group(3).replace(" ", "")
                # a range ("1-3") stays a string, a plain count becomes an int
                pt["quantity"] = qty if "-" in qty else int(qty)
            if m.group(4):
                pt["respawnTime"] = int(m.group(4))
            points.append(pt)
        if not name and not points:
            continue
        out.append({
            "name": name or None,
            "location": strip_markup(p.get("location", "")) or None,
            "isMembers": wiki_bool(p.get("members")),
            "leagueRegion": wiki_title(strip_markup(p.get("leagueregion", ""))),
            "spawns": points,
        })
    return out


# --------------------------------------------------------------- skill info

# Each gathering skill states its requirements in its own template, but they
# share a name/level/xp core and differ only in what the activity needs.
SKILL_INFO = {
    # seedling/sapling are the intermediate stages of the tree chain:
    # seed -> seedling -> sapling -> planted tree
    "Farming info": ("Farming", ("patch", "seed", "seedling", "sapling",
                                 "seedsper", "payment", "time", "plant",
                                 "crop", "yield", "regrow", "plantxp",
                                 "checkxp", "harvestxp")),
    "Woodcutting info": ("Woodcutting", ("tool", "time", "tree")),
    "Mining info": ("Mining", ("tool", "time", "rock")),
    "Fishing info": ("Fishing", ("bait", "tool", "spot")),
    "Hunter info": ("Hunter", ("trap", "retaliation", "container", "wildxp",
                               "bait", "facility")),
    "Thieving info": ("Thieving", ("type", "tool", "damage", "time",
                                   "timename")),
    "Agility info": ("Agility", ("type", "course", "failxp")),
}

# fields that are numbers rather than prose, wherever they appear
_INFO_NUMERIC = {"level", "xp", "seedsper", "plantxp", "harvestxp", "checkxp",
                 "damage", "wildxp", "failxp"}


def parse_relative_locations(text):
    """{{Relativelocation}} -> {location, north, south, east, west}.

    An adjacency list for the world map: which place borders which. 319 pages
    carry it, so together they form a traversable graph of Gielinor.
    """
    for block in find_templates(text, "Relativelocation"):
        p = template_params(block)
        out = {"location": strip_markup(p.get("location", "")) or None}
        for side in ("north", "south", "east", "west"):
            val = strip_markup(p.get(side, ""))
            if val:
                out[side] = val
        if len(out) > 1:
            return out
    return None


def parse_skill_info(text):
    """The six `* info` templates -> [{skill, level, xp, ...}].

    Blocks are versioned like the infoboxes (`xp1`, `level2`), so each variant
    index becomes its own entry. An unnumbered block is index "".
    """
    out = []
    for template, (skill, extras) in SKILL_INFO.items():
        for block in find_templates(text, template):
            p = template_params(block)
            # Variants may be signalled by `version1`, by `level1`/`xp1`, or
            # only by the suffix on a secondary-skill key (`skill1lvl2`), so
            # collect indices from all three rather than one.
            idxs = sorted({m.group(1) for k in p for m in [
                re.fullmatch(r"(?:version|level|xp)(\d+)", k)
                or re.fullmatch(r"skill\d+(?:name|lvl|exp|note)(\d+)", k)
            ] if m})
            for idx in idxs or [""]:
                v = versioned(p, idx)
                entry = {"skill": skill,
                         "name": strip_markup(v("name", "")) or None,
                         "version": strip_markup(v("version", "")) or None}
                for key in ("level", "xp") + extras:
                    raw = v(key)
                    if raw is None:
                        continue
                    if key in _INFO_NUMERIC:
                        val = wiki_num(raw, None)
                        if val is not None:
                            entry[key] = int(val) if val == int(val) else val
                    elif key == "patch":
                        val = wiki_patch(raw)
                        if val:
                            entry[key] = val
                    elif key == "type":
                        val = wiki_title(strip_markup(raw))
                        if val:
                            entry[key] = val
                    else:
                        # seed/crop/tool/bait name items via {{plink}}
                        val = wiki_plink(raw)
                        if val:
                            entry[key] = val
                extra = _secondary_skills(p, idx, skill)
                if extra:
                    entry["skills"] = extra
                    # skill1 is the template's own skill; let it set the headline
                    own = next((s for s in extra if s["skill"] == skill), None)
                    if own:
                        entry.setdefault("level", own.get("level"))
                        if entry.get("xp") is None and own.get("xp") is not None:
                            entry["xp"] = own["xp"]
                if (entry.get("level") is not None or entry.get("xp") is not None
                        or extra):
                    out.append(entry)
    return out


def _secondary_skills(params, idx, default_skill):
    """`skill2name2=Strength|skill2exp2=8` -> [{skill, level, xp}].

    Some activities train two skills at once -- Barbarian Fishing gives both
    Fishing and Strength -- encoded as a skill index crossed with the variant
    index. `skillNfield` with no variant suffix applies to every variant.
    """
    buckets = {}
    for key, val in params.items():
        m = re.fullmatch(r"skill(\d+)(name|lvl|exp|note)(\d*)", key)
        if not m:
            continue
        slot, field, variant = m.group(1), m.group(2), m.group(3)
        if variant and variant != idx:
            continue
        buckets.setdefault(slot, {})[field] = val

    out = []
    for slot in sorted(buckets, key=int):
        b = buckets[slot]
        name = strip_markup(b.get("name", "")) or (
            default_skill if slot == "1" else None)
        if not name:
            continue
        entry = {"skill": name}
        for field, out_key in (("lvl", "level"), ("exp", "xp")):
            val = wiki_num(b.get(field), None)
            # `skill2exp1 = -1` is the wiki's way of hiding a value
            if val is not None and val >= 0:
                entry[out_key] = int(val) if val == int(val) else val
        note = strip_markup(b.get("note", ""))
        if note:
            entry["note"] = note
        if len(entry) > 1:
            out.append(entry)
    return out


# -------------------------------------------------------------------- drops

# Each variant is the same shape; the template name is what distinguishes a
# normal kill drop from pickpocket loot or a minigame reward.
DROP_TEMPLATES = {
    "DropsLine": None,
    "DropsLineClue": "clue",
    "DropsLineSkill": "skill",     # Thieving/pickpocketing and other skill loot
    "DropsLineReward": "reward",   # minigame and activity rewards
}

# Shared tables referenced rather than expanded. The contents live in the
# template itself, so a page only states the roll rate and any modifiers.
DROP_TABLES = {
    "RareDropTable": "rare",
    "HerbDropLines": "herb",
    "GemDropTable": "gem",
    "WildernessSlayerDropTable": "wildernessSlayer",
    "UncommonSeedDropLines": "uncommonSeed",
    "RareSeedDropLines": "rareSeed",
    "CatacombsDropTable": "catacombs",
    "TalismanDropLines": "talisman",
}


def parse_drops(text, lookup):
    """All {{DropsLine*}} variants -> [{id|name, quantity, rarity, source}]."""
    drops = []
    for tpl, source in DROP_TEMPLATES.items():
        for block in find_templates(text, tpl):
            p = template_params(block)
            name = strip_markup(p.get("name", ""))
            if not name:
                continue
            drop = {"name": name}
            iid = lookup(name) if lookup else None
            if iid is not None:
                drop["id"] = iid
            # quantity is often a range ("1-4") and rarity often non-numeric
            # ("Always", "Varies"), so both stay raw strings
            for key, out in (("quantity", "quantity"), ("rarity", "rarity"),
                             ("altrarity", "altRarity"),
                             ("raritynotes", "rarityNotes"),
                             ("namenotes", "nameNotes"),
                             ("quantitynotes", "quantityNotes"),
                             ("dropversion", "dropVersion"),
                             ("skill", "skill"),
                             ("leagueregion", "leagueRegion")):
                val = strip_markup(p.get(key, ""))
                if val:
                    # editors write both "Thieving" and "thieving"
                    drop[out] = (val.capitalize() if out == "skill"
                                 else wiki_title(val) if out == "leagueRegion"
                                 else val)
            # how many times this row is rolled per kill
            rolls = wiki_int(p.get("rolls"))
            if rolls is not None:
                drop["rolls"] = rolls
            if wiki_bool(p.get("approx")):
                drop["isApproximate"] = True
            if source:
                drop["source"] = source
            drops.append(drop)
    return drops


def parse_drop_tables(text):
    """Shared drop-table references -> [{table, rarity, ...modifiers}].

    e.g. {{HerbDropLines|46/128|1-2}} or
         {{GemDropTable|1/128|chaostalisman=yes}}
    The rolled items are defined inside the template, so what a page carries is
    the access rate and which variant of the table applies.
    """
    out = []
    for tpl, kind in DROP_TABLES.items():
        for block in find_templates(text, tpl):
            p = template_params(block)
            pos = template_positionals(block)
            entry = {"table": kind}
            if pos:
                entry["rarity"] = strip_markup(pos[0])
            if len(pos) > 1:
                # the second positional differs by table: RareDropTable states
                # a second access rate (the mega-rare sub-table), while
                # HerbDropLines states the quantity rolled
                second = strip_markup(pos[1])
                entry["megaRarity" if kind == "rare" else "quantity"] = second
            for flag in ("chaostalisman", "naturetalisman", "f2p", "boss",
                         "superior", "giantsden"):
                if flag in p:
                    entry[flag] = wiki_bool(p[flag])
            for key in ("combat", "hitpoints", "hitpointsmax", "rolls"):
                val = wiki_int(p.get(key))
                if val is not None:
                    entry[key] = val
            if p.get("dropversion"):
                entry["dropVersion"] = strip_markup(p["dropversion"])
            out.append(entry)
    return out


# ------------------------------------------------------------------- output shape

def compact_by_page(records, fields):
    """Hoist page-identical arrays into a side table -> (records, pages).

    Several fields are facts about a wiki *page* but get stamped onto every
    variant record parsed from it -- Hill Giant's 16 records each carry the same
    35 drops and 13 locations. Where every record on a page agrees, the value
    moves into `pages[page]` and leaves the records entirely.

    Lossless by construction: a field is only hoisted when all of a page's
    records hold an identical value, so rehydrating restores the original.
    Rehydrate with `{**pages.get(r["page"], {}), **r}`.
    """
    groups = {}
    for r in records:
        groups.setdefault(r.get("page"), []).append(r)

    pages = {}
    for page, rs in groups.items():
        if page is None:
            continue
        shared = {}
        for field in fields:
            value = rs[0].get(field)
            if not value:
                continue
            encoded = json.dumps(value, sort_keys=True)
            if all(json.dumps(r.get(field), sort_keys=True) == encoded for r in rs):
                shared[field] = value
        if shared:
            pages[page] = shared

    # strip in the caller's original order; grouping is only used to decide
    # what is shared, and must not become the output order
    out = [{k: v for k, v in r.items() if k not in pages.get(r.get("page"), {})}
           for r in records]
    return out, pages


def rehydrate_by_page(compacted):
    """Inverse of compact_by_page, for round-trip checks and consumers."""
    pages = compacted.get("pages", {})
    return [{**pages.get(r.get("page"), {}), **r} for r in compacted["records"]]


def load_dataset(path):
    """Read a dataset written in either the default or --compact shape."""
    data = json.loads(Path(path).read_text())
    return rehydrate_by_page(data) if isinstance(data, dict) else data


# --------------------------------------------------------------------- joins

def id_collisions(left, right, key="id", name="name"):
    """Ids that name a different thing on each side.

    The wiki documents ids by hand, so a few land on the wrong page, and a few
    describe one entity from two angles -- the Temple Trekking swamp creature
    is a monster page per body part and a single npc page. Either way the id
    does not identify one thing, and a join on it silently returns whichever
    side was indexed last.
    """
    by_id = {}
    for record in left:
        if record.get(key) is not None:
            by_id[record[key]] = (record.get(name) or "").lower()
    bad = set()
    for record in right:
        got = by_id.get(record.get(key))
        if got is not None and got != (record.get(name) or "").lower():
            bad.add(record[key])
    return bad


def join_by_id(left, right, key="id", name="name"):
    """Join two datasets on `key`, dropping ids that disagree on `name`.

    Returns (joined, dropped). Nothing is guessed: an ambiguous id is left out
    of the result and reported, because the failure mode otherwise is a lookup
    that quietly answers with the wrong entity.
    """
    ambiguous = id_collisions(left, right, key, name)
    by_id = {r[key]: r for r in left if r.get(key) is not None}
    joined = {}
    for record in right:
        ident = record.get(key)
        if ident is None or ident in ambiguous or ident not in by_id:
            continue
        joined[ident] = {**by_id[ident], **record}
    return joined, ambiguous


# -------------------------------------------------------------------- output

def log(msg):
    print(msg, file=sys.stderr, flush=True)


def progress_bar(label):
    """Callback for fetch_wikitext/fetch_redirects that renders a % counter."""
    def f(done, total):
        pct = 100 * done // max(total, 1)
        print(f"\r  {label}: {done}/{total} ({pct}%)",
              end="", file=sys.stderr, flush=True)
        if done >= total:
            print(file=sys.stderr)
    return f
