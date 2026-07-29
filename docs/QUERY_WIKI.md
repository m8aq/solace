# Querying the OSRS wiki

`query_wiki.py` searches and reads the live wiki. Nothing is stored locally, so
results are always current, and the cost of an answer is whatever you choose to
read.

That last part is the whole game. The same question can cost 76 tokens or
105,000 depending on how you ask it.

## Cost of each access pattern

Measured against `Vorkath`:

| command | tokens | what you get |
|---|---|---|
| `--index` | **~122** | section numbers, for scoping a fetch |
| search | ~256 | 6 pages with matching snippets |
| `--section X` | ~388 | one section's prose |
| `--extract` | ~1,378 | the whole page as prose, **no tables** |
| `--tables --in-section N` | varies | one section's tables |
| `--raw` | **~105,863** | the entire page as HTML |

`--extract` is the cheapest way to read a page and the right default for "how
does this work". It uses the TextExtracts API, which strips tables, images and
references -- so it answers mechanics questions and not "what are the
requirements". It also batches up to 20 pages in a single call, which nothing
else here does.

When a table *is* the answer, scope the fetch instead of pulling the page:
`--index` lists section numbers for ~120 tokens, and `--tables --in-section N`
reads only that section. Achievement Diary is 307,726 tokens whole and 1,235
for its lead -- and the lead is where an infobox lives, so a stat block costs
almost nothing.

`Achievement Diary` is roughly **500,000 tokens** with `--raw`. Never reach for
it casually.

## Start from the category index

Search assumes you know what you are looking for. When you do not, start from
the tree — `data/wiki-categories.json`, built by `tools/build_wiki_index.py`
(2,340 categories, 435 KB, ~13 s to rebuild).

```bash
python3 tools/query_wiki.py --categories            # the top of the tree
python3 tools/query_wiki.py --categories boss       # categories matching a word
python3 tools/query_wiki.py --category "Bosses"     # the pages in one
```

`--categories` with no argument prints `Category:Content` — the 36 subject
areas the game's tree hangs off — plus the largest categories by page count.
With a term it matches category names and shows their subcategories.

Categories whose names describe the wiki's own upkeep ("Pages with resolved
feedback") are hidden; `--maintenance` includes them. 174 of the 2,340 are
flagged this way.

The tree is what makes enumeration possible: `Monsters → Bosses → Abyssal
Sire` tells you what exists without a single page fetch.

### Worked example: reaching Zulrah

```bash
python3 tools/query_wiki.py --categories bosses     # 171 pages, 40 subcats
python3 tools/query_wiki.py --category "Bosses"     # all 171, Zulrah among them
python3 tools/query_wiki.py --page "Zulrah" --sections
python3 tools/query_wiki.py --page "Zulrah" --section "Fight overview"
```

`--category` returns **every** member, following continuation. It does not cap
by default, and that matters: an earlier version asked for one page of 40 and
`Category:Bosses` came back alphabetically truncated at `Count Draynor`, so
Zulrah did not exist as far as the caller could tell. Use `--limit` when you
want a cap; the output says so when one applies.

Large categories are fine — `Category:Items` returns all 12,388 in about six
seconds — but list them only when you actually need the enumeration.

### Is the index still current?

```bash
python3 tools/query_wiki.py --check-index
```

The index records the wiki's own clock and article count at build time, and
the check compares them against now:

```
index built 2026-06-01T00:00:00Z, wiki now 2026-07-29T03:32:26Z
  category edits since : 166
  articles             : 40,000 -> 41,113 (+1,113)
  touched              : 2006 Christmas event, 2014 Easter event, ...

stale enough to rebuild: python3 tools/build_wiki_index.py
```

It reports rather than decides — most category edits are wording, not
structure. It calls for a rebuild past 50 category edits, 500 articles, or a
capped result. The wiki's clock is used rather than the local one, so a skewed
machine cannot make a fresh index look stale.

## Endpoints

The index carries an `endpoints` block, so anything holding the file knows how
to fetch as well as what exists. All eight are verified against the wiki:

| key | for |
|---|---|
| `search` | full-text search |
| `page_html` | Parsoid HTML — the whole page |
| `page_source` | metadata plus raw wikitext |
| `page_rendered` | the human-facing page |
| `category_members` | pages in a category |
| `subcategories` | child categories |
| `category_page` | a category's own page |
| `wikitext_batch` | up to 50 pages of wikitext in one call |

They are templates (`{title}`, `{name}`, `{query}`, `{limit}`, `{titles}`)
rather than a URL stored per record — substituting a name is free, where
writing the same 90-byte prefix onto 2,340 categories would roughly double the
file to say nothing new. Percent-encode the value; use underscores for spaces
in the REST paths.

`wikitext_batch` is the one that changes what is possible: 50 pages per
request where everything else is one. It is how the `all-*.json` builders read
thousands of pages in seconds, and the reason those exist at all.

## Basic use

**Search.** Full text, ranked by the wiki's own search engine.

```bash
python3 tools/query_wiki.py "monsters become tolerant 10 minutes"
```

The snippets alone answer a surprising number of questions. Read them before
fetching anything.

**Read the prose.** For "how does this work", this is the cheapest route and
usually the whole answer.

```bash
python3 tools/query_wiki.py --page "Game tick" --extract
```

**Map a page before reading more of it.** Two views, for two purposes:
`--sections` gives headings with word counts, which is what you want before
choosing prose; `--index` gives the section *numbers* that `--in-section`
takes, which is what you want before reading a table.

```bash
python3 tools/query_wiki.py --page "Vorkath" --sections
python3 tools/query_wiki.py --page "Vorkath" --index
```

```
Vorkath: 14 sections
  (lead)  (1711 words)
    Location  (77 words)
    Fight overview  (256 words)
    Drops  (205 words)
```

Now you know exactly which section to ask for, and roughly what it costs.

**Read a table.** Tables are stripped from prose output on purpose — flattened,
they are a wall of numbers with no columns. `--tables` lists them; `--table`
prints one, by index or by a word in its heading.

```bash
python3 tools/query_wiki.py --page "Ancient Wizard" --tables
python3 tools/query_wiki.py --page "Ancient Wizard" --table 0 --in-section 0
```

```
| Combat level | 98 |
| Max hit | 18 |
| Attack speed | 4 ticks (2.4 seconds) |
```

Spanned values are repeated into every cell they cover so each row stands on
its own, then collapsed on output — an infobox spans for layout and would
otherwise repeat one field twenty-four times. `--wide` keeps the raw grid.

A skill requirement is an icon plus a number, so the skill name is read from
the icon's alt text: without that, `Mining 10 Smithing 13` arrives as `10 13`.

**Read one section.**

```bash
python3 tools/query_wiki.py --page "Vorkath" --section "Fight overview"
```

`--section` matches on substring, case-insensitively, and prints every heading
that matches.

**Read the raw HTML.** Only when a table's exact shape is the answer — column
alignment, `rowspan`, which cell a value sits in. Tables are stripped from the
text output precisely because they flatten into a wall of numbers.

```bash
python3 tools/query_wiki.py --page "Achievement Diary" --raw
```

## Searching smartly

The query string is passed through to MediaWiki's CirrusSearch, so its
operators all work. These are the ones worth knowing, each verified against
this wiki:

| operator | does |
|---|---|
| `"exact phrase"` | phrase match rather than loose terms |
| `intitle:tick` | match the title |
| `incategory:"Mechanics" tick` | restrict to a category |
| `insource:"become tolerant"` | search the **wikitext**, not the rendered text |
| `insource:/\{\{Infobox Monster/` | regex over the source |
| `hastemplate:"Infobox Monster"` | pages using a template |
| `-strategies` | exclude a term |

`intitle:` also searches **redirect** titles and returns the target, so a hit's
own title need not contain the term. `intitle:tick` returns `Attack speed`,
which is correct — it has redirects like `5 tick weapons`. Useful, but do not
assume a result's title matches what you asked for.

`insource:` is the sharp one. Rendered text and wikitext differ — a value
produced by a template exists in the render but not the source, and vice versa.
When a search for visible text fails, the phrase may only exist as a template
argument.

`hastemplate:` and `incategory:` are how you enumerate a *kind* of page:
every monster, every money making guide, every page in a category.

## The rest of the API

The wiki exposes 83 actions, 28 `prop` modules, 44 `list` modules and 11 `meta`
modules. Most are editing and account management. These are the ones worth
reaching for directly when the tool does not already wrap them — each verified
against this wiki:

| call | for |
|---|---|
| `action=opensearch&search=vorka` | title autocomplete; resolves a half-remembered name |
| `list=prefixsearch&pssearch=Vork` | the same, as a normal query result |
| `prop=categories&titles=X` | a page's categories — the inverse of the index |
| `list=backlinks&bltitle=X` | what links here |
| `prop=transcludedin&titles=Template:X` | every page using a template |
| `prop=templates&titles=X` | every template a page uses (Vorkath: 52) |
| `prop=links` / `prop=images` / `prop=redirects` | a page's outgoing links, images, redirects |
| `prop=info&inprop=url` | canonical url, length, last touched |
| `action=expandtemplates&text={{SCP\|Mining\|70}}` | render wikitext without saving; shows what a template emits |
| `action=compare` | diff two revisions |
| `list=recentchanges&rcnamespace=14` | category edits — what `--check-index` uses |

Two are worth knowing about even though nothing here uses them yet:
`prop=cirrusdoc` returns the raw search-index document for a page, and
`action=parse&prop=templates|links|categories` gets several of the above in one
call rather than several.

## Structured data: Bucket

The wiki runs **Bucket**, its own structured store — 47 tables holding what the
templates wrote, queryable with server-side joins:

```lua
bucket("dropsline")
  .join("infobox_item", "dropsline.item_name", "infobox_item.item_name")
  .where("dropsline.item_name", "Dragon bones")
  .select("dropsline.page_name", "infobox_item.item_id")
  .limit(500).run()
```

Through `action=bucket&query=...`. `.join()`, `.where()`, `.orderBy()`,
`.offset()` and `.limit()` all work; there is no `groupBy` or `count`, so
aggregation stays client-side. `tools/build_osrs_bucket.py` builds thirteen
datasets this way in about twenty seconds.

Tables worth knowing beyond the infoboxes: `dropsline` and `drop_table_sources`
(what drops what, and the shared tables), `storeline` (shop stock and prices),
`locline` and `map` (coordinates, the same points in two shapes),
`infobox_bonuses` (equipment slot, attack speed and range), `varbit` (the
wiki's varbit and varp documentation), `collection_log_source` (drop rates),
`combat_achievement`, `money_making_guide`, and `item_id`/`npc_id`/`object_id`
(page-to-id mappings, an independent check on the infobox ids).

Bucket holds fields, not prose. `quest.description` is a blurb and
`transcript` lists only which npcs appear — the dialogue itself is page text,
which is what everything above is for.

## Keeping the cost down

1. **Read the search snippets first.** They are free with the search and often
   contain the fact.
2. **Always `--sections` before `--section`.** 76 tokens buys you the map; guessing
   a section name and getting the wrong one costs several hundred.
3. **Never `--raw` a page you have not sized.** `--sections` tells you the word
   counts. A page whose sections total 20,000 words is a 100k-token file.
4. **Narrow with operators rather than reading more.** `incategory:` and
   `intitle:` cut the result set at the server, for free.
5. **Ask the JSON first for entity facts** (see below). It is local, instant,
   and answers questions the wiki cannot.

## When not to use this at all

`data/wiki/` holds 27 datasets built from the wiki's structured store and the
game cache — around 190,000 records. Rebuild the lot with
`python3 tools/refresh.py`; the schema of every one is in
[KEYS.md](KEYS.md).

Use them for anything **reverse or aggregated**, which a page query cannot
answer:

- *What drops Dragon bones?* — the wiki has no index from an item back to its
  droppers, so live that means reading every monster page. `all-monsters.json`
  answers it in milliseconds, and `all-collection-log.json` carries the rates.
- *Which shops sell X? What can I make from Y? Which monsters roll the herb
  table? What are this weapon's bonuses and attack speed?*

And for everything the cache knows that has no wiki page at all — varbits,
varps, animations, interface components, sprites, sounds. That is the one
source no query here can reach.

Two caveats. The datasets are a snapshot, so check anything time-sensitive
against the live wiki. And `all-collection-log.json` is transcribed from four
user sandbox pages (`User:Crocs/Collection log source/1-4`) rather than
mainspace — one editor maintains it, which is worth knowing before trusting a
rate.

