#!/usr/bin/env python3
"""
Build a category index of the OSRS wiki.

Searching works when you already know roughly what you are looking for. This
is for the other case: what does the wiki actually cover, and where does a
subject live. It is the starting point a search is chosen from.

The wiki has ~2,340 categories, but a third of them are bookkeeping -- "Pages
with resolved feedback", "Pages that contain switch infobox data" -- which
describe the wiki's own maintenance rather than the game. Those are kept but
flagged, so a reader can ignore them without them silently disappearing.

Parents come from `prop=categories` in batches of 50, which is what makes the
result a tree rather than a list: `Category:Bosses` sits under `Category:
Monsters`, and knowing that is usually more useful than any single page.

The index is small (about 490 KB) and changes slowly, so unlike page content
it is worth keeping on disk.

Usage:
    python3 tools/build_wiki_index.py
    python3 tools/build_wiki_index.py --out data/wiki-categories.json
"""

import argparse
import json
import re
from pathlib import Path

from osrs_wiki import api_get, log, progress_bar

# Categories about the wiki's upkeep rather than the game. Flagged, not
# dropped: a few are genuinely useful ("Pages with maps" finds every page
# carrying coordinates), and silently losing a third of the tree is worse
# than labelling it.
WIKI = "https://oldschool.runescape.wiki"

# Every call this corpus needs, as templates. They live in the index so it is
# self-describing: a reader holding the file knows both what exists and how to
# fetch it, without reading this script.
#
# Templates rather than a URL per record. Substituting a name costs nothing,
# where writing the same 90-byte prefix onto 2,340 categories and every page
# under them would roughly double the file to say nothing new. `{title}` and
# `{name}` are percent-encoded, with spaces as underscores for REST paths.
ENDPOINTS = {
    "search": (f"{WIKI}/api.php?action=query&list=search&srsearch={{query}}"
               "&srlimit={limit}&format=json&formatversion=2"),
    "page_html": f"{WIKI}/rest.php/v1/page/{{title}}/html",
    "page_source": f"{WIKI}/rest.php/v1/page/{{title}}",
    "page_rendered": f"{WIKI}/w/{{title}}",
    "category_members": (f"{WIKI}/api.php?action=query&list=categorymembers"
                         "&cmtitle=Category:{name}&cmlimit={limit}"
                         "&cmnamespace=0&format=json&formatversion=2"),
    "subcategories": (f"{WIKI}/api.php?action=query&list=categorymembers"
                      "&cmtitle=Category:{name}&cmtype=subcat&cmlimit={limit}"
                      "&format=json&formatversion=2"),
    "category_page": f"{WIKI}/w/Category:{{name}}",
    "wikitext_batch": (f"{WIKI}/api.php?action=query&prop=revisions"
                       "&rvprop=content&rvslots=main&titles={titles}"
                       "&format=json&formatversion=2"),
    "search_operators": ["intitle:", "incategory:", "hastemplate:",
                         "insource:", "insource:/regex/", "-exclude",
                         '"exact phrase"'],
}

MAINTENANCE = re.compile(
    r"^(pages (with|that|using|needing|containing|missing)|articles |"
    r"candidates |needs |missing |uncategorised|uncategorized|"
    r"disambiguation|redirects|stubs?$|.*templates?$|.*infobox data$|"
    r".*with unchecked|.*requiring |.*to be |unlisted|obsolete|deprecated|"
    # `non-` alone matched Non-player characters (4,460 pages) and
    # Non-interactive scenery (2,009), hiding real content from browsing
    r"non-(existent|free|canon))",
    re.I)

# Dated changelog categories: "13 June updates", "2014 updates". Real content,
# but ~350 of them, and they bury the subject categories when browsing. Marked
# separately so they can be hidden without being called maintenance.
CHANGELOG = re.compile(
    r"^((\d{1,2} )?(january|february|march|april|may|june|july|august|"
    r"september|october|november|december) updates$|\d{4} updates$|"
    r"updates by (day|month|year)$)", re.I)


def all_categories():
    """[{category, pages, files, subcats}] for the whole wiki."""
    out, cont = [], {}
    while True:
        d = api_get({"action": "query", "list": "allcategories",
                     "aclimit": "500", "acprop": "size",
                     "formatversion": "2", **cont})
        out += d["query"]["allcategories"]
        if "continue" not in d:
            return out
        cont = d["continue"]


def parents_of(names, progress=None):
    """{category: [parent categories]} via prop=categories, 50 at a time."""
    parents, batch_size = {}, 50
    titles = [f"Category:{n}" for n in names]
    for i in range(0, len(titles), batch_size):
        batch = titles[i:i + batch_size]
        cont = {}
        while True:
            d = api_get({"action": "query", "titles": "|".join(batch),
                         "prop": "categories", "cllimit": "500",
                         "formatversion": "2", **cont})
            for page in d.get("query", {}).get("pages", []):
                name = page["title"].removeprefix("Category:")
                got = [c["title"].removeprefix("Category:")
                       for c in page.get("categories", [])]
                parents.setdefault(name, []).extend(got)
            if "continue" not in d:
                break
            cont = d["continue"]
        if progress:
            progress(min(i + batch_size, len(titles)), len(titles))
    return parents


def site_state():
    """A cheap fingerprint of the wiki, for detecting drift later.

    The server's own clock rather than ours, so a skewed local machine cannot
    make a fresh index look stale or the reverse.
    """
    d = api_get({"action": "query", "meta": "siteinfo",
                 "siprop": "statistics|general"})
    stats = d["query"]["statistics"]
    return {"built": d["query"]["general"]["time"],
            "articles": stats["articles"], "edits": stats["edits"]}


def build():
    state = site_state()
    cats = all_categories()
    log(f"categories: {len(cats)}")
    names = [c["category"] for c in cats]
    parents = parents_of(names, progress_bar("parents"))

    children = {}
    for name, ps in parents.items():
        for parent in ps:
            children.setdefault(parent, []).append(name)

    records = []
    for c in cats:
        name = c["category"]
        records.append({
            "name": name,
            "pages": c.get("pages", 0),
            "subcats": sorted(children.get(name, [])),
            "parents": sorted(set(parents.get(name, []))),
            "maintenance": bool(MAINTENANCE.match(name)),
            "changelog": bool(CHANGELOG.match(name)),
        })
    records.sort(key=lambda r: -r["pages"])
    by_name = {r["name"]: r for r in records}

    # The game's tree hangs off Category:Content, which is itself under
    # Category:Old School RuneScape Wiki alongside the community pages.
    # Computing roots as "categories with no parent" instead returns orphans
    # -- Lost Lover, Crypt of Tonali -- which is not an entry point.
    roots = by_name["Content"]["subcats"] if "Content" in by_name else []
    top = [r["name"] for r in records
           if not r["maintenance"] and not r["changelog"]][:40]
    return {"built": state, "endpoints": ENDPOINTS, "roots": sorted(roots),
            "top_by_size": top, "categories": records}


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--out", default="data/wiki-categories.json")
    args = ap.parse_args()
    index = build()
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(index, indent=1))

    cats = index["categories"]
    real = [c for c in cats if not c["maintenance"] and not c["changelog"]]
    log(f"\nwrote {path}  ({path.stat().st_size / 1024:.0f} KB)")
    log(f"  {len(cats)} categories: {len(real)} subject, "
        f"{sum(1 for c in cats if c['maintenance'])} maintenance, "
        f"{sum(1 for c in cats if c['changelog'])} changelog, "
        f"{len(index['roots'])} roots")


if __name__ == "__main__":
    main()
