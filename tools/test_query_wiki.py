#!/usr/bin/env python3
"""
Tests for query_wiki.py.

Split in two. The offline cases cover the pure functions and run in
milliseconds. The live cases hit the wiki, because the things that break here
are not parsing bugs -- they are titles the API encodes differently than
expected, categories larger than one response, and pages whose shape differs
from the one page you happened to try.

Live assertions avoid exact counts where the wiki would drift: a category's
membership changes, so the test asserts "more than one response worth" rather
than a number that will rot.

Usage:
    python3 tools/test_query_wiki.py          # offline only
    python3 tools/test_query_wiki.py --live   # everything
"""

import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

import build_wiki_index as B
import query_wiki as Q

LIVE = "--live" in sys.argv


class Plain(unittest.TestCase):
    def test_tags_stripped_entities_decoded(self):
        self.assertEqual(Q.plain("<p>a &amp; b&#160;c</p>"), "a & b c")

    def test_tables_dropped_by_default(self):
        # a flattened table is a wall of numbers with no columns
        self.assertEqual(Q.plain("keep<table><tr><td>9</td></tr></table>"),
                         "keep")

    def test_tables_kept_when_asked(self):
        self.assertIn("9", Q.plain("<table><tr><td>9</td></tr></table>",
                                   tables=True))

    def test_script_and_style_dropped(self):
        self.assertEqual(Q.plain("<style>td{color:red}</style>text"), "text")


@unittest.skipUnless(LIVE, "needs --live")
class Search(unittest.TestCase):
    def test_plain_terms(self):
        hits = Q.search("monsters become tolerant", 5)
        self.assertTrue(hits)
        self.assertIn("Aggressiveness", [t for t, _ in hits])

    def test_phrase_is_narrower_than_loose_terms(self):
        loose = Q.search("dragonfire shield", 20)
        phrase = Q.search('"dragonfire shield"', 20)
        self.assertLessEqual(len(phrase), len(loose) + 1)

    def test_intitle(self):
        titles = [t for t, _ in Q.search("intitle:tick", 8)]
        self.assertIn("Game tick", titles)
        self.assertIn("Tick manipulation", titles)

    def test_intitle_matches_redirects_too(self):
        # not a bug, and worth pinning: `intitle:` searches redirect titles and
        # returns the target, so a hit's own title need not contain the term.
        # "Attack speed" answers intitle:tick through "5 tick weapons".
        self.assertIn("Attack speed", [t for t, _ in Q.search("intitle:tick", 8)])

    def test_incategory(self):
        hits = Q.search('incategory:"Bosses" dragon', 5)
        self.assertTrue(hits)

    def test_hastemplate(self):
        hits = Q.search('hastemplate:"Infobox Monster" zulrah', 3)
        self.assertIn("Zulrah", [t for t, _ in hits])

    def test_insource_searches_wikitext(self):
        # the rendered text says "become tolerant"; insource is literal
        self.assertTrue(Q.search('insource:"become tolerant"', 3))

    def test_insource_regex(self):
        hits = Q.search(r'insource:/\{\{Infobox Monster/ vorkath', 3)
        self.assertIn("Vorkath", [t for t, _ in hits])

    def test_negation_excludes(self):
        hits = Q.search("vorkath -strategies", 10)
        self.assertNotIn("Vorkath/Strategies", [t for t, _ in hits])

    def test_no_results_is_empty_not_an_error(self):
        self.assertEqual(Q.search("zzzqqxnotarealterm12345", 3), [])


@unittest.skipUnless(LIVE, "needs --live")
class Categories(unittest.TestCase):
    def test_pagination_past_one_response(self):
        # the regression: one response of 40 cut Bosses off at Count Draynor
        bosses = Q.members("Bosses")
        self.assertGreater(len(bosses), 100)
        self.assertIn("Zulrah", bosses)
        self.assertIn("Abyssal Sire", bosses)

    def test_pagination_past_500(self):
        # more than one continuation round
        items = Q.members("Items", 1200)
        self.assertEqual(len(items), 1200)
        self.assertEqual(len(set(items)), 1200, "continuation repeated a page")

    def test_limit_truncates_exactly(self):
        self.assertEqual(len(Q.members("Bosses", 7)), 7)

    def test_empty_category_is_empty_not_an_error(self):
        self.assertEqual(Q.members("Zzznotarealcategory12345"), [])

    def test_category_name_with_punctuation(self):
        # apostrophes and spaces must survive encoding
        self.assertIsInstance(Q.members("Members' items", 3), list)


@unittest.skipUnless(LIVE, "needs --live")
class Pages(unittest.TestCase):
    def test_sections_are_ordered_and_named(self):
        found = Q.sections("Zulrah")
        headings = [h for _, h, _ in found]
        self.assertGreater(len(found), 5)
        self.assertIn("Fight overview", headings)
        self.assertEqual(headings[0], "(lead)")

    def test_section_text_is_prose_not_markup(self):
        text = dict((h, t) for _, h, t in Q.sections("Game tick"))
        body = " ".join(text.values())
        self.assertNotIn("<", body)
        self.assertNotIn("{{", body)

    def test_subpage_title_with_slash(self):
        found = Q.sections("Vorkath/Strategies")
        self.assertTrue(found)

    def test_title_with_apostrophe(self):
        self.assertTrue(Q.sections("Fishing Guild"))

    def test_title_with_parentheses(self):
        self.assertTrue(Q.sections("Clue scroll (master)"))

    def test_redirect_is_followed_or_reported(self):
        # "Ancient wizard" redirects to "Ancient Wizard"
        found = Q.sections("Ancient wizard")
        self.assertTrue(found, "a redirect returned nothing usable")

    def test_missing_page_raises_rather_than_returning_junk(self):
        with self.assertRaises(Exception):
            Q.sections("Zzznotarealpage12345")


class Tables(unittest.TestCase):
    def test_collapse_removes_span_runs(self):
        self.assertEqual(Q.collapse(["Combat level"] * 8 + ["98"] * 16),
                         ["Combat level", "98"])

    def test_collapse_keeps_non_adjacent_repeats(self):
        self.assertEqual(Q.collapse(["a", "b", "a"]), ["a", "b", "a"])

    def test_span_clamps_nonsense(self):
        self.assertEqual(Q._span(None), 1)
        self.assertEqual(Q._span("wat"), 1)
        self.assertEqual(Q._span("0"), 1)
        self.assertEqual(Q._span("9999"), 64)

    def test_grid_expands_a_rowspan(self):
        p = Q._Tables()
        p.feed('<table><tr><td rowspan="2">A</td><td>1</td></tr>'
               '<tr><td>2</td></tr></table>')
        self.assertEqual(p.tables[0]["rows"], [["A", "1"], ["A", "2"]])

    def test_grid_expands_a_colspan(self):
        p = Q._Tables()
        p.feed('<table><tr><td colspan="3">wide</td></tr>'
               '<tr><td>a</td><td>b</td><td>c</td></tr></table>')
        self.assertEqual(p.tables[0]["rows"],
                         [["wide", "wide", "wide"], ["a", "b", "c"]])

    def test_image_alt_is_kept_as_cell_text(self):
        # a skill requirement is an icon plus a number; without the alt the
        # level survives and the skill it applies to does not
        p = Q._Tables()
        p.feed('<table><tr><td><img alt="Mining">10</td></tr></table>')
        self.assertEqual(p.tables[0]["rows"], [["Mining 10"]])

    def test_image_filenames_are_not_cell_text(self):
        p = Q._Tables()
        p.feed('<table><tr><td><img alt="Bones.png">x</td></tr></table>')
        self.assertEqual(p.tables[0]["rows"], [["x"]])

    def test_a_nested_table_survives_its_layout_wrapper(self):
        # the wiki wraps real tables in outer layout tables that hold no text
        # of their own; the inner one is the data and the wrapper is dropped
        p = Q._Tables()
        p.feed("<table><tr><td><table><tr><td>inner</td></tr></table></td>"
               "</tr></table>")
        self.assertIn(["inner"], [r for t in p.tables for r in t["rows"]])

    def test_a_wrapper_with_its_own_text_is_kept(self):
        p = Q._Tables()
        p.feed("<table><tr><td>outer</td><td><table><tr><td>inner</td></tr>"
               "</table></td></tr></table>")
        rows = [r for t in p.tables for r in t["rows"]]
        self.assertIn(["inner"], rows)
        self.assertTrue(any("outer" in r for r in rows))

    def test_caption_and_heading_label_a_table(self):
        p = Q._Tables()
        p.feed("<h2>Drops</h2><table><caption>Herbs</caption>"
               "<tr><td>x</td></tr></table>")
        self.assertEqual(p.tables[0]["heading"], "Drops")
        self.assertEqual(p.tables[0]["caption"], "Herbs")

    @unittest.skipUnless(LIVE, "needs --live")
    def test_infobox_stats_are_readable(self):
        found = Q.tables("Ancient Wizard")
        flat = {tuple(Q.collapse(r)) for t in found for r in t["rows"]}
        self.assertIn(("Combat level", "98"), flat)
        self.assertIn(("Max hit", "18"), flat)

    @unittest.skipUnless(LIVE, "needs --live")
    def test_diary_requirements_keep_skill_and_level(self):
        found = Q.tables("Achievement Diary")
        cells = [c for t in found for r in t["rows"] for c in r]
        self.assertTrue(any(re.search(r"Mining \d+", c) for c in cells),
                        "skill names lost from requirement tables")


@unittest.skipUnless(LIVE, "needs --live")
class Extracts(unittest.TestCase):
    def test_extract_is_prose_without_markup(self):
        [(title, body)] = Q.extract("Game tick")
        self.assertEqual(title, "Game tick")
        self.assertIn("0.6 seconds", body)
        self.assertNotIn("<", body)

    def test_extract_batches_many_pages_in_one_call(self):
        got = Q.extract(["Game tick", "Vorkath", "Zulrah"], intro=True)
        self.assertEqual(len(got), 3)
        self.assertTrue(all(body for _, body in got))

    def test_extract_drops_tables(self):
        # prose only: the combat stats live in an infobox and do not survive
        [(_, body)] = Q.extract("Ancient Wizard")
        self.assertNotIn("Combat level", body)

    def test_section_index_is_cheap_and_ordered(self):
        found = Q.section_index("Vorkath")
        self.assertGreater(len(found), 5)
        headings = [line for _, _, line in found]
        self.assertIn("Fight overview", headings)

    def test_tables_can_be_scoped_to_a_section(self):
        # the infobox is in the lead, so section 0 answers a stat question
        # without fetching the rest of the page
        scoped = Q.tables("Ancient Wizard", 0)
        flat = {tuple(Q.collapse(r)) for t in scoped for r in t["rows"]}
        self.assertIn(("Combat level", "98"), flat)

    def test_scoped_fetch_is_smaller_than_the_whole_page(self):
        whole = len(Q.page_html("Achievement Diary"))
        lead = len(Q.section_html("Achievement Diary", 0))
        self.assertLess(lead * 10, whole, "scoping saved little")


class Index(unittest.TestCase):
    def test_index_has_endpoints_and_roots(self):
        data = Q.index()
        for key in ("search", "page_html", "category_members",
                    "wikitext_batch"):
            self.assertIn(key, data["endpoints"])
        self.assertTrue(data["roots"])
        self.assertGreater(len(data["categories"]), 1000)

    def test_endpoint_templates_have_the_right_holes(self):
        e = Q.index()["endpoints"]
        self.assertIn("{title}", e["page_html"])
        self.assertIn("{name}", e["category_members"])
        self.assertIn("{query}", e["search"])

    def test_maintenance_flagged_not_dropped(self):
        cats = Q.index()["categories"]
        flagged = [c for c in cats if c["maintenance"]]
        self.assertTrue(flagged)
        self.assertTrue(any("Pages with" in c["name"] for c in flagged))

    def test_real_categories_are_not_flagged_as_upkeep(self):
        # regression: a bare `non-` in the classifier matched Non-player
        # characters (4,460 pages) and Non-interactive scenery (2,009),
        # hiding 6,469 pages of game content from browsing
        by = {c["name"]: c for c in Q.index()["categories"]}
        for name in ("Non-player characters", "Non-interactive scenery",
                     "Bosses", "Monsters", "Quests", "Skills"):
            self.assertFalse(by[name]["maintenance"], f"{name} flagged")
            self.assertFalse(by[name]["changelog"], f"{name} flagged")

    def test_changelog_categories_are_separated(self):
        # ~400 dated update categories are real content but bury the subject
        # tree, so they are marked apart rather than called maintenance
        by = {c["name"]: c for c in Q.index()["categories"]}
        for name in ("2014 updates", "13 June updates"):
            self.assertTrue(by[name]["changelog"])
            self.assertFalse(by[name]["maintenance"])

    def test_upkeep_categories_still_flagged(self):
        by = {c["name"]: c for c in Q.index()["categories"]}
        self.assertTrue(by["Pages with maps"]["maintenance"])
        self.assertTrue(by["Pages with resolved feedback"]["maintenance"])

    def test_top_by_size_is_subject_categories_only(self):
        data = Q.index()
        by = {c["name"]: c for c in data["categories"]}
        for name in data["top_by_size"]:
            self.assertFalse(by[name]["maintenance"] or by[name]["changelog"],
                             f"{name} should not head the browse list")

    def test_tree_links_parents_to_children(self):
        by = {c["name"]: c for c in Q.index()["categories"]}
        self.assertIn("Bosses", by["Monsters"]["subcats"])
        self.assertIn("Monsters", by["Bosses"]["parents"])


class Classifiers(unittest.TestCase):
    """The index classifiers, which fail silently -- a false positive here
    removes a category from browsing and nothing reports it."""

    def test_maintenance_catches_upkeep(self):
        for name in ("Pages with maps", "Pages that contain switch infobox data",
                     "Needs key", "Disambiguation", "Navbox templates",
                     "Candidates for deletion", "Uncategorised pages"):
            self.assertTrue(B.MAINTENANCE.match(name), name)

    def test_maintenance_leaves_game_content_alone(self):
        # `non-` matched Non-player characters and Non-interactive scenery
        for name in ("Non-player characters", "Non-interactive scenery",
                     "Monsters", "Bosses", "Quests", "Skills", "Items",
                     "Members' items", "Nightmare Zone"):
            self.assertFalse(B.MAINTENANCE.match(name), name)

    def test_changelog_matches_dated_updates_only(self):
        for name in ("13 June updates", "2014 updates", "June updates",
                     "Updates by year"):
            self.assertTrue(B.CHANGELOG.match(name), name)
        for name in ("Updates", "Game updates", "Mobile updates",
                     "Technical updates", "Quests"):
            self.assertFalse(B.CHANGELOG.match(name), name)

    def test_endpoint_templates_format_without_error(self):
        e = B.ENDPOINTS
        self.assertTrue(e["page_html"].format(title="Vorkath")
                        .endswith("/Vorkath/html"))
        self.assertIn("Category:Bosses",
                      e["category_members"].format(name="Bosses", limit=5))
        self.assertIn("srsearch=x", e["search"].format(query="x", limit=1))


@unittest.skipUnless(LIVE, "needs --live")
class IndexBuilder(unittest.TestCase):
    def test_all_categories_paginates(self):
        cats = B.all_categories()
        self.assertGreater(len(cats), 2000)
        self.assertEqual(len(cats), len({c["category"] for c in cats}),
                         "continuation repeated a category")

    def test_parents_are_resolved(self):
        got = B.parents_of(["Bosses", "Skills"])
        self.assertIn("Monsters", got["Bosses"])
        self.assertTrue(got["Skills"])

    def test_parents_across_a_batch_boundary(self):
        # 50 titles per request: a name at index 50 must still come back
        names = [f"Category{i}" for i in range(50)] + ["Bosses"]
        got = B.parents_of(names)
        self.assertIn("Monsters", got.get("Bosses", []))


class Staleness(unittest.TestCase):
    """Whether the stored index still matches the wiki it was built from."""

    def test_index_records_what_it_was_built_against(self):
        built = Q.index().get("built")
        self.assertIsNotNone(built, "index has no build fingerprint")
        self.assertRegex(built["built"], r"^\d{4}-\d\d-\d\dT")
        self.assertGreater(built["articles"], 1000)

    @unittest.skipUnless(LIVE, "needs --live")
    def test_a_fresh_index_reports_no_drift(self):
        s = Q.staleness()
        self.assertTrue(s["known"])
        self.assertGreaterEqual(s["articles_now"], 1000)
        self.assertGreaterEqual(s["category_edits"], 0)

    @unittest.skipUnless(LIVE, "needs --live")
    def test_a_backdated_index_reports_drift(self):
        # the check is only worth having if it fires; prove it does
        real = Q.INDEX.read_text()
        data = json.loads(real)
        data["built"]["built"] = "2026-06-01T00:00:00Z"
        data["built"]["articles"] = 40000
        try:
            Q.INDEX.write_text(json.dumps(data))
            s = Q.staleness()
            self.assertGreater(s["category_edits"], 0)
            self.assertGreater(s["articles_now"] - s["articles_then"], 0)
            self.assertTrue(s["touched"])
        finally:
            Q.INDEX.write_text(real)

    @unittest.skipUnless(LIVE, "needs --live")
    def test_an_index_without_a_fingerprint_says_so(self):
        real = Q.INDEX.read_text()
        data = json.loads(real)
        data.pop("built", None)
        try:
            Q.INDEX.write_text(json.dumps(data))
            self.assertFalse(Q.staleness()["known"])
        finally:
            Q.INDEX.write_text(real)


class CLI(unittest.TestCase):
    """main() -- the printing and flag handling the library tests skip."""

    def run_tool(self, *args):
        out = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("query_wiki.py")),
             *args], capture_output=True, text=True, timeout=120)
        return out.returncode, out.stdout, out.stderr

    def test_no_arguments_explains_itself(self):
        code, _, err = self.run_tool()
        self.assertNotEqual(code, 0)
        self.assertIn("search terms", err)

    def test_categories_lists_the_tree(self):
        code, out, _ = self.run_tool("--categories")
        self.assertEqual(code, 0)
        self.assertIn("Content:", out)
        self.assertIn("largest categories:", out)

    def test_categories_term_shows_endpoint(self):
        code, out, _ = self.run_tool("--categories", "bosses")
        self.assertEqual(code, 0)
        self.assertIn("Bosses", out)
        self.assertIn("action=query&list=categorymembers", out)

    def test_categories_hides_maintenance_by_default(self):
        _, plain_out, _ = self.run_tool("--categories", "pages with")
        _, with_flag, _ = self.run_tool("--categories", "pages with",
                                        "--maintenance")
        self.assertLess(len(plain_out), len(with_flag))
        self.assertIn("[maintenance]", with_flag)

    def test_tables_lists_and_prints(self):
        code, out, _ = self.run_tool("--page", "Ancient Wizard", "--tables")
        self.assertEqual(code, 0)
        self.assertIn("tables", out)
        code, out, _ = self.run_tool("--page", "Ancient Wizard", "--table", "0")
        self.assertEqual(code, 0)
        self.assertIn("Combat level | 98", out)

    def test_unknown_table_says_so(self):
        code, _, err = self.run_tool("--page", "Ancient Wizard",
                                     "--table", "zzznotreal")
        self.assertNotEqual(code, 0)
        self.assertIn("no table matching", err)

    def test_check_index_reports_freshness(self):
        code, out, _ = self.run_tool("--check-index")
        self.assertEqual(code, 0)
        self.assertIn("index built", out)
        self.assertIn("articles", out)

    def test_unknown_category_says_so(self):
        code, out, _ = self.run_tool("--categories", "zzznotreal12345")
        self.assertEqual(code, 0)
        self.assertIn("no category matches", out)


if __name__ == "__main__":
    if LIVE:
        sys.argv.remove("--live")
    unittest.main(verbosity=2 if "-v" in sys.argv else 1)
