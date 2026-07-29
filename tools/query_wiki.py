#!/usr/bin/env python3
"""
Search and read the OSRS wiki live.

An earlier version of this file searched a local index over a 212 MB copy of
the wiki's HTML. That was two mistakes at once: the corpus was a cache of
something already served on demand, and the index competed with the wiki's own
search, which runs a real search engine and wins. Asked for "aggressive
tolerant", the wiki returned the same top hit and also surfaced the Tolerance
page, which the local ranking missed.

So nothing is stored. Search hits the wiki's search index; reading a page
fetches it and returns the one section asked for, which is usually a few
hundred words rather than the half million tokens the largest page costs in
full.

The all-*.json datasets are a different matter and stay on disk: they are
joined and derived -- drops keyed by item id, spawns with coordinates, scenery
matched against the game cache -- and this wiki runs no Cargo or Semantic
MediaWiki, so there is no query that reproduces them.

Usage:
    python3 tools/query_wiki.py "aggression tolerance timer"
    python3 tools/query_wiki.py --page "Vorkath" --sections
    python3 tools/query_wiki.py --page "Vorkath" --section "Acid"
    python3 tools/query_wiki.py --page "Game tick" --raw
"""

import argparse
import html as htmllib
import json
import re
import signal
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

WIKI = "https://oldschool.runescape.wiki"
UA = {"User-Agent": "solace-wiki-tools/1.0 (github.com/m8aq/solace)"}

_TAG = re.compile(r"<[^>]+>")
# Tables flatten into a wall of numbers with no columns. They stay findable by
# their heading; --raw prints the markup when the shape is what matters.
_DROP = re.compile(r"<(script|style|table)\b.*?</\1>", re.S | re.I)
_HEAD = re.compile(r"<h([2-6])\b[^>]*>(.*?)</h\1>", re.S)


def _get(url):
    with urllib.request.urlopen(
            urllib.request.Request(url, headers=UA), timeout=60) as response:
        return response.read().decode("utf-8")


def plain(fragment, tables=False):
    if not tables:
        fragment = _DROP.sub(" ", fragment)
    return re.sub(r"\s+", " ", htmllib.unescape(_TAG.sub(" ", fragment))).strip()


def search(query, limit=6):
    """The wiki's own full-text search: [(title, snippet)]."""
    url = (f"{WIKI}/api.php?action=query&list=search"
           f"&srsearch={urllib.parse.quote(query)}&srlimit={limit}"
           f"&format=json&formatversion=2")
    data = json.loads(_get(url))
    return [(p["title"], plain(p["snippet"]))
            for p in data["query"]["search"]]


def page_html(title):
    path = urllib.parse.quote(title.replace(" ", "_"), safe="")
    return _get(f"{WIKI}/rest.php/v1/page/{path}/html")


def sections(title):
    """[(level, heading, text)] for a page, in document order."""
    body = re.split(r"<body[^>]*>", page_html(title), maxsplit=1)[-1]
    marks = [(m.start(), m.end(), m.group(1), plain(m.group(2)))
             for m in _HEAD.finditer(body)]
    out = []
    if marks and marks[0][0] > 0:
        lead = plain(body[:marks[0][0]])
        if lead:
            out.append(("1", "(lead)", lead))
    for i, (_, end, level, heading) in enumerate(marks):
        stop = marks[i + 1][0] if i + 1 < len(marks) else len(body)
        text = plain(body[end:stop])
        if text:
            out.append((level, heading, text))
    return out


class _Tables(HTMLParser):
    """Pull every table off a page as a rectangular grid of cell text.

    Section text drops tables on purpose -- flattened, they are a wall of
    numbers with no columns. But a monster's combat stats live in an infobox
    and a diary's requirements live in a table, so dropping them means the
    numbers are only reachable through the raw HTML, which on a large page is
    six figures of tokens for a handful of cells.

    rowspan and colspan are expanded by repeating the value into every cell
    they cover: a pipe table cannot express a span, and a blank reads as
    missing data where a repeat keeps each row standing on its own.
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables = []          # [{"heading", "caption", "rows"}]
        self.stack = []           # open tables, innermost last
        self.heading = ""
        self._in_heading = False
        self._head_text = []
        self._cell = None
        self._caption = None

    # -- headings, so a table can say where it came from
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self._in_heading, self._head_text = True, []
        elif tag == "table":
            self.stack.append({"heading": self.heading, "caption": "",
                               "rows": [], "pending": {}, "row": None})
        elif tag == "caption" and self.stack:
            self._caption = []
        elif tag == "tr" and self.stack:
            self.stack[-1]["row"] = []
        elif tag in ("td", "th") and self.stack:
            table = self.stack[-1]
            if table["row"] is None:
                table["row"] = []
            self._cell = {"text": [], "colspan": _span(a.get("colspan")),
                          "rowspan": _span(a.get("rowspan"))}
        elif tag == "img" and self._cell is not None:
            # A skill requirement is an icon plus a number, so the cell reads
            # "10 13" with the skills only in the icons' alt text. Without
            # this the levels survive and what they apply to does not.
            alt = a.get("alt", "").strip()
            if alt and not alt.lower().endswith((".png", ".gif", ".jpg")):
                self._cell["text"].append(f" {alt} ")

    def handle_endtag(self, tag):
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self.heading = re.sub(r"\s+", " ", "".join(self._head_text)).strip()
            self._in_heading = False
        elif tag == "caption" and self.stack and self._caption is not None:
            self.stack[-1]["caption"] = re.sub(
                r"\s+", " ", "".join(self._caption)).strip()
            self._caption = None
        elif tag in ("td", "th") and self._cell is not None and self.stack:
            self._close_cell()
        elif tag == "tr" and self.stack:
            self._close_row()
        elif tag == "table" and self.stack:
            table = self.stack.pop()
            self._close_row(table)
            if table["rows"]:
                self.tables.append({k: table[k]
                                    for k in ("heading", "caption", "rows")})

    def handle_data(self, data):
        if self._in_heading:
            self._head_text.append(data)
        elif self._caption is not None:
            self._caption.append(data)
        elif self._cell is not None:
            self._cell["text"].append(data)

    # -- grid assembly
    def _close_cell(self):
        table = self.stack[-1]
        text = re.sub(r"\s+", " ", "".join(self._cell["text"])).strip()
        column = len(table["row"])
        while column in table["pending"]:
            left, value = table["pending"][column]
            table["row"].append(value)
            table["pending"][column] = (left - 1, value)
            if left - 1 <= 0:
                del table["pending"][column]
            column += 1
        for _ in range(self._cell["colspan"]):
            table["row"].append(text)
            if self._cell["rowspan"] > 1:
                table["pending"][column] = (self._cell["rowspan"] - 1, text)
            column += 1
        self._cell = None

    def _close_row(self, table=None):
        table = table or self.stack[-1]
        row = table["row"]
        if row is None:
            return
        column = len(row)
        while column in table["pending"]:
            left, value = table["pending"][column]
            row.append(value)
            table["pending"][column] = (left - 1, value)
            if left - 1 <= 0:
                del table["pending"][column]
            column += 1
        if any(c for c in row):
            table["rows"].append(row)
        table["row"] = None


def collapse(row):
    """Drop runs of the same value left by a span.

    A grid repeats a spanned value into every cell it covers, which keeps rows
    aligned but makes an infobox unreadable -- those use colspans for layout,
    so one field can repeat twenty-four times. Collapsing runs turns that back
    into `Combat level | 98`. Genuine data tables rarely repeat a value in
    adjacent columns; --wide prints the uncollapsed grid when they do.
    """
    out = []
    for cell in row:
        if not out or cell != out[-1]:
            out.append(cell)
    return out


def _span(value):
    try:
        return max(1, min(int(value), 64))
    except (TypeError, ValueError):
        return 1


def section_index(title):
    """[(index, level, heading)] without fetching any of the page body."""
    data = api("parse", page=title, prop="sections", redirects="1")
    return [(s["index"], s["level"], s["line"]) for s in data["parse"]["sections"]]


def section_html(title, index):
    """One section's HTML.

    A page is fetched whole only when it has to be. Achievement Diary is
    307,726 tokens entire and 1,235 for its lead, and the lead is where an
    infobox lives -- so scoping the fetch is the difference between reading a
    stat block and reading the year's diary tasks.
    """
    data = api("parse", page=title, prop="text", section=str(index),
               redirects="1")
    return data["parse"]["text"]


def tables(title, section=None):
    """[{heading, caption, rows}] for a page, or for one section of it."""
    parser = _Tables()
    parser.feed(section_html(title, section) if section is not None
                else page_html(title))
    return parser.tables


def api(action, **params):
    """The action API, as JSON."""
    query = "&".join(f"{k}={urllib.parse.quote(str(v))}"
                     for k, v in params.items())
    return json.loads(_get(f"{WIKI}/api.php?action={action}&{query}"
                           "&format=json&formatversion=2"))


def extract(titles, intro=False):
    """Plain-text prose for up to 20 pages in one call.

    Tables, images and references are stripped by the extension, so this
    answers "how does this work" and not "what are the requirements" -- for
    that use --tables, which reads the markup.
    """
    names = titles if isinstance(titles, (list, tuple)) else [titles]
    params = {"prop": "extracts", "titles": "|".join(names),
              "explaintext": "1", "exlimit": "20", "redirects": "1"}
    if intro:
        params["exintro"] = "1"
    data = api("query", **params)
    return [(p["title"], p.get("extract", "") or "")
            for p in data["query"]["pages"]]


INDEX = Path(__file__).resolve().parent.parent / "data" / "wiki-categories.json"


def index():
    if not INDEX.exists():
        raise SystemExit(f"no category index at {INDEX} -- "
                         "run tools/build_wiki_index.py")
    return json.loads(INDEX.read_text())


def browse(term=None, show_maintenance=False):
    """The category tree, or the categories whose name matches `term`."""
    data = index()
    by_name = {c["name"]: c for c in data["categories"]}
    if not term:
        print("Content:")
        for name in data["roots"]:
            c = by_name.get(name, {})
            print(f"  {c.get('pages', 0):>6}  {name}"
                  f"  ({len(c.get('subcats', []))} subcats)")
        print("\nlargest categories:")
        for name in data["top_by_size"][:12]:
            print(f"  {by_name[name]['pages']:>6}  {name}")
        return
    low = term.lower()
    hits = [c for c in data["categories"] if low in c["name"].lower()
            and (show_maintenance or not c["maintenance"])]
    template = data["endpoints"]["category_members"]
    for c in sorted(hits, key=lambda c: -c["pages"])[:30]:
        print(f"  {c['pages']:>6}  {c['name']}"
              f"{'  [maintenance]' if c['maintenance'] else ''}")
        if c["subcats"]:
            print(f"          subcats: {', '.join(c['subcats'][:8])}"
                  f"{' ...' if len(c['subcats']) > 8 else ''}")
        if c["pages"]:
            print("          " + template.format(
                name=urllib.parse.quote(c["name"]), limit=40))
    if not hits:
        print("no category matches")


def staleness():
    """How far the stored index has drifted from the live wiki.

    Two signals, both one request. Category-namespace edits since the build
    say whether the tree itself moved; the article count says whether the wiki
    grew underneath it. Neither proves the index is wrong -- most category
    edits are wording -- so this reports rather than decides.
    """
    built = index().get("built")
    if not built:
        return {"known": False}
    since = built["built"]
    changes = json.loads(_get(
        f"{WIKI}/api.php?action=query&list=recentchanges&rcnamespace=14"
        f"&rcend={urllib.parse.quote(since)}&rclimit=500&rcprop=title"
        "&format=json&formatversion=2"))["query"]["recentchanges"]
    now = json.loads(_get(
        f"{WIKI}/api.php?action=query&meta=siteinfo&siprop=statistics|general"
        "&format=json&formatversion=2"))["query"]
    return {
        "known": True,
        "built": since,
        "now": now["general"]["time"],
        "category_edits": len(changes),
        "capped": len(changes) >= 500,
        "touched": sorted({c["title"].removeprefix("Category:")
                           for c in changes})[:12],
        "articles_then": built["articles"],
        "articles_now": now["statistics"]["articles"],
    }


def members(category, limit=0):
    """Every page in a category, live -- membership changes as the wiki does.

    Follows continuation. The API caps a response at 500, and asking for one
    page of 40 silently returned the alphabetical head: Category:Bosses has
    171 members and stopped at Count Draynor, so Zulrah did not exist as far
    as the caller could tell. `limit` caps deliberately; 0 means all.
    """
    out, cont = [], {}
    while True:
        params = ("&".join(f"{k}={v}" for k, v in cont.items())
                  + "&" if cont else "")
        url = (f"{WIKI}/api.php?action=query&list=categorymembers"
               f"&cmtitle=Category:{urllib.parse.quote(category)}"
               f"&cmlimit=500&cmnamespace=0&format=json&formatversion=2&"
               + params)
        data = json.loads(_get(url))
        out += [p["title"] for p in data["query"]["categorymembers"]]
        if limit and len(out) >= limit:
            return out[:limit]
        if "continue" not in data:
            return out
        cont = {k: urllib.parse.quote(str(v))
                for k, v in data["continue"].items()}


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("query", nargs="*", help="full-text search terms")
    ap.add_argument("--categories", nargs="?", const="", metavar="TERM",
                    help="browse the category tree, or search category names")
    ap.add_argument("--category", metavar="NAME",
                    help="list the pages in a category")
    ap.add_argument("--check-index", action="store_true",
                    help="report how far the stored category index has "
                         "drifted from the live wiki")
    ap.add_argument("--maintenance", action="store_true",
                    help="with --categories: include the wiki's own upkeep "
                         "categories, which are hidden by default")
    ap.add_argument("--page", help="read this page instead of searching")
    ap.add_argument("--section", help="with --page: only sections matching this")
    ap.add_argument("--sections", action="store_true",
                    help="with --page: list headings and their sizes")
    ap.add_argument("--extract", action="store_true",
                    help="with --page: plain-text prose, the cheapest way to "
                         "read a page (no tables)")
    ap.add_argument("--index", action="store_true",
                    help="with --page: list section indexes for --in-section")
    ap.add_argument("--in-section", metavar="N",
                    help="with --tables/--table: read only this section index")
    ap.add_argument("--tables", action="store_true",
                    help="with --page: list the page's tables and their size")
    ap.add_argument("--table", metavar="N_OR_TERM",
                    help="with --page: print one table, by index or by a word "
                         "in its heading or caption")
    ap.add_argument("--wide", action="store_true",
                    help="with --table: keep spanned values repeated in every "
                         "cell instead of collapsing the runs")
    ap.add_argument("--raw", action="store_true",
                    help="with --page: print the HTML, for exact table shape")
    ap.add_argument("-n", type=int, default=6, help="search results")
    ap.add_argument("--limit", type=int, default=0,
                    help="with --category: stop after this many pages "
                         "(default: all of them)")
    args = ap.parse_args()

    if args.check_index:
        s = staleness()
        if not s["known"]:
            raise SystemExit("index predates staleness tracking -- rebuild it")
        drift = s["articles_now"] - s["articles_then"]
        print(f"index built {s['built']}, wiki now {s['now']}")
        print(f"  category edits since : {s['category_edits']}"
              + ("+ (capped)" if s["capped"] else ""))
        print(f"  articles             : {s['articles_then']:,} -> "
              f"{s['articles_now']:,} ({drift:+,})")
        if s["touched"]:
            print(f"  touched              : {', '.join(s['touched'])}")
        stale = s["capped"] or s["category_edits"] > 50 or abs(drift) > 500
        print("\n" + ("stale enough to rebuild: "
                       "python3 tools/build_wiki_index.py"
                       if stale else "fresh enough"))
        return

    if args.categories is not None:
        browse(args.categories or None, args.maintenance)
        return

    if args.category:
        # `-n` caps deliberately; the default fetches every member, because a
        # quiet cut is how Zulrah went missing from Category:Bosses
        pages = members(args.category, args.limit)
        print(f"Category:{args.category} — {len(pages)} pages"
              + (f" (capped at {args.limit})"
                 if args.limit and len(pages) >= args.limit else ""))
        for t in pages:
            print(f"  {t}")
        return

    if args.page and args.index:
        for index, level, line in section_index(args.page):
            print(f"  {index:<5} {'  ' * (int(level) - 1)}{line}")
        return

    if args.page and args.extract:
        for title, body in extract(args.page):
            print(f"# {title}\n\n{body}")
        return

    if args.page and (args.tables or args.table):
        found = tables(args.page, args.in_section)
        if args.tables:
            print(f"{args.page}: {len(found)} tables")
            for i, t in enumerate(found):
                width = max(len(r) for r in t["rows"])
                label = t["caption"] or t["heading"] or "(untitled)"
                print(f"  [{i:>2}] {len(t['rows']):>3} x {width:<3} {label[:60]}")
            return
        picked = []
        if args.table.isdigit():
            index = int(args.table)
            if index < len(found):
                picked = [found[index]]
        else:
            term = args.table.lower()
            picked = [t for t in found
                      if term in (t["caption"] + " " + t["heading"]).lower()]
        if not picked:
            raise SystemExit(f"no table matching {args.table!r}")
        for t in picked:
            print(f"\n## {t['caption'] or t['heading'] or '(untitled)'}")
            for row in t["rows"]:
                shown = row if args.wide else collapse(row)
                print("  | " + " | ".join(shown) + " |")
        return

    if args.page:
        if args.raw:
            print(page_html(args.page))
            return
        found = sections(args.page)
        if args.sections:
            print(f"{args.page}: {len(found)} sections")
            for level, heading, text in found:
                print(f"  {'  ' * (int(level) - 1)}{heading}"
                      f"  ({len(text.split())} words)")
            return
        for level, heading, text in found:
            if args.section and args.section.lower() not in heading.lower():
                continue
            print(f"\n## {heading}\n{text}")
        return

    if not args.query:
        raise SystemExit("give search terms, or --page")
    for title, snippet in search(" ".join(args.query), args.n):
        print(f"\n{title}\n    {snippet}")


if __name__ == "__main__":
    # `| head` closes the pipe early, which Python reports as a traceback
    # rather than the silent exit every other command-line tool gives.
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    main()
