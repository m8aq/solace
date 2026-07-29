#!/usr/bin/env python3
"""
Offline tests for the OSRS wiki parsing layer.

No network: every case is wikitext pasted from a real page, so the suite runs
in milliseconds and stays honest about what the wiki actually contains.

Most cases here are regressions. The parsers looked correct and produced
plausible output while being wrong -- `facilities` misread as `facility`,
`ticks = 0` nulled by a truthiness check, `{{plink}}` values silently emptied,
compact output quietly reordering records. Each of those shipped a believable
number, which is exactly why they need a test rather than a spot-check.

Usage:
    python3 tools/test_osrs_wiki.py
    python3 tools/test_osrs_wiki.py -v
"""

import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

import osrs_wiki as W
from osrs_wiki import parse_ids
from build_osrs_items import (
    CANONICAL_IDS, _recipe_identity, item_lookup, parse_combat_styles,
    parse_item_page, parse_recipe_page,
)
from build_osrs_magic import parse_rune_cost
from build_osrs_quests import _bullets, parse_scp
from build_osrs_scenery import drop_inert, join_cache, load_cache_objects
from build_osrs_shops import (
    _buy_percent, _narrow_by_proximity, _percent, _prices_compatible,
    merge_shops,
)


def lookup_from(pairs):
    """A minimal item_lookup over (id, name) pairs."""
    return item_lookup([{"id": i, "name": n, "page": n} for i, n in pairs])


class TemplateScanning(unittest.TestCase):
    def test_nested_braces_do_not_end_the_block(self):
        text = "{{Recipe|mat1={{plink|Coal}}|output1=Bar}} trailing"
        blocks = W.find_templates(text, "Recipe")
        self.assertEqual(len(blocks), 1)
        self.assertTrue(blocks[0].endswith("}}"))
        self.assertNotIn("trailing", blocks[0])

    def test_unclosed_template_is_skipped_not_hung(self):
        self.assertEqual(W.find_templates("{{Recipe|a=1", "Recipe"), [])

    def test_name_match_is_anchored(self):
        """`Recipe` must not match `RecipeCost`."""
        text = "{{RecipeCost|a=1}}{{Recipe|b=2}}"
        self.assertEqual(len(W.find_templates(text, "Recipe")), 1)

    def test_case_insensitive_and_whitespace_tolerant(self):
        self.assertEqual(len(W.find_templates("{{ recipe |a=1}}", "Recipe")), 1)

    def test_iter_templates_preserves_document_order(self):
        """Shops depend on this: rows bind to the preceding head."""
        text = ("{{StoreTableHead|a=1}}{{StoreLine|n=1}}{{StoreLine|n=2}}"
                "{{StoreTableBottom}}{{StoreTableHead|a=2}}{{StoreLine|n=3}}")
        seq = [n for n, _ in W.iter_templates(
            text, ["StoreTableHead", "StoreLine", "StoreTableBottom"])]
        self.assertEqual(seq, ["StoreTableHead", "StoreLine", "StoreLine",
                               "StoreTableBottom", "StoreTableHead", "StoreLine"])

    def test_split_top_level_respects_nesting(self):
        parts = W.split_top_level("a|{{X|inner}}|[[Link|text]]|b")
        self.assertEqual(len(parts), 4)
        self.assertEqual(parts[1], "{{X|inner}}")

    def test_positionals_exclude_named_params(self):
        block = "{{Map|mtype=dot|1843,3728|1847,3728}}"
        self.assertEqual(W.template_positionals(block),
                         ["1843,3728", "1847,3728"])


class Coercion(unittest.TestCase):
    def test_wiki_int_keeps_a_real_zero(self):
        """`ticks = 0` means instant; a truthiness check nulled 80 recipes."""
        self.assertEqual(W.wiki_int("0"), 0)
        self.assertIsNone(W.wiki_int("Varies"))
        self.assertIsNone(W.wiki_int(""))
        self.assertIsNone(W.wiki_int(None))

    def test_wiki_int_strips_thousands_separator(self):
        """`experience = 2,230` must not become 2."""
        self.assertEqual(W.wiki_int("2,230"), 2230)

    def test_wiki_bool(self):
        for yes in ("Yes", "yes", "TRUE", "1"):
            self.assertTrue(W.wiki_bool(yes))
        for no in ("No", "", "n/a", None):
            self.assertFalse(W.wiki_bool(no))

    def test_strip_markup_clears_nested_templates(self):
        """One pass left the outer shell behind in location names."""
        raw = ("[[Taverley Dungeon]] (Upper Level)"
               "{{Refn|Requires a [[dusty key]] or {{SCP|Agility|70}}.|group=l}}")
        self.assertEqual(W.strip_markup(raw), "Taverley Dungeon (Upper Level)")

    def test_line_breaks_separate_values(self):
        """Deleting <br/> welded "12 minutes" and "6 minutes" together."""
        self.assertEqual(
            W.strip_markup("12 minutes<br/>6 minutes in Mining Guild"),
            "12 minutes 6 minutes in Mining Guild")
        self.assertEqual(W.strip_markup("a<br>b"), "a b")
        self.assertEqual(W.strip_markup("<li>x</li><li>y</li>"), "x y")

    def test_embedded_images_are_dropped_not_unwrapped(self):
        """An image is not prose: unwrapping leaked "25px" or the filename."""
        self.assertEqual(
            W.strip_markup("[[File:Arceuus.png|25px]] [[Arceuus spellbook]] active"),
            "Arceuus spellbook active")
        # with no pipe it welded straight onto the following word
        self.assertEqual(W.strip_markup("[[File:X.png]]Text"), "Text")
        # wiki_file still reads the filename where that is the point
        self.assertEqual(W.wiki_file("[[File:Shark.png|150px]]"), "Shark.png")

    def test_inline_tags_still_close_up(self):
        """Only block-level tags imply a break; <i> should not add a space."""
        self.assertEqual(W.strip_markup("Ka<i>ram</i>ja"), "Karamja")

    def test_strip_markup_unwraps_piped_links(self):
        self.assertEqual(W.strip_markup("[[Myths' Guild|the basement]]"),
                         "the basement")

    def test_wiki_plink_keeps_the_item_name(self):
        """strip_markup emptied `seed = {{plink|Snape grass seed}}`."""
        self.assertEqual(W.wiki_plink("{{plink|Snape grass seed}}"),
                         "Snape grass seed")
        self.assertEqual(W.wiki_plink("5 {{plink|jangerberries}}"),
                         "5 jangerberries")
        self.assertEqual(W.wiki_plink("{{plink|Bass|pic=Bass.png}}"), "Bass")

    def test_wiki_file_takes_the_filename_not_the_size(self):
        """strip_markup returned "150px" for image params."""
        self.assertEqual(W.wiki_file("[[File:Shark.png|150px]]"), "Shark.png")
        self.assertEqual(W.wiki_file("[[File:A B.png]]"), "A B.png")

    def test_wiki_list_drops_placeholders(self):
        self.assertEqual(W.wiki_list("Talk-to, Bank, Collect"),
                         ["Talk-to", "Bank", "Collect"])
        self.assertEqual(W.wiki_list("N/A"), [])
        self.assertEqual(W.wiki_list(""), [])


class Normalisation(unittest.TestCase):
    def test_categorical_case_is_unified(self):
        """`Kandarin`/`kandarin` split one bucket across thousands of records."""
        self.assertEqual(W.wiki_title("kandarin"), "Kandarin")
        self.assertEqual(W.wiki_title("Kandarin"), "Kandarin")
        self.assertEqual(W.wiki_title("undead"), "Undead")

    def test_only_the_first_letter_is_touched(self):
        """Multi-word names and internal capitals must survive."""
        self.assertEqual(W.wiki_title("wilderness Bandit Camp"),
                         "Wilderness Bandit Camp")
        self.assertEqual(W.wiki_title("TzHaar"), "TzHaar")

    def test_comma_lists_normalise_each_part(self):
        self.assertEqual(W.wiki_title("kourend,tirannwn,morytania"),
                         "Kourend, Tirannwn, Morytania")

    def test_farming_patch_suffix_is_dropped(self):
        """`Allotment` (84) and `Allotment patch` (80) are the same patch."""
        self.assertEqual(W.wiki_patch("Allotment patch"), "Allotment")
        self.assertEqual(W.wiki_patch("allotment"), "Allotment")
        self.assertEqual(W.wiki_patch("Fruit tree patch"), "Fruit Tree")
        self.assertEqual(W.wiki_patch("Tithe patch"), "Tithe")

    def test_farming_patch_links_are_stripped(self):
        """Patches are written as wiki links as often as plain text."""
        self.assertEqual(W.wiki_patch("[[Allotment patch]]"), "Allotment")
        self.assertEqual(W.wiki_patch("[[Bush patch|Bush]]"), "Bush")
        self.assertEqual(W.wiki_patch("[[Anima patch]]"), "Anima")

    def test_ampersand_separated_regions(self):
        """`Karamja&kandarin` is two regions, not one word."""
        self.assertEqual(W.wiki_title("Karamja&kandarin"), "Karamja&Kandarin")

    def test_every_word_is_normalised(self):
        """Only casing the first word left `Slash Sword`/`slash sword` split."""
        self.assertEqual(W.wiki_title("slash sword"), "Slash Sword")
        self.assertEqual(W.wiki_title("Formal garden"), "Formal Garden")

    def test_wiki_list_can_title_case(self):
        self.assertEqual(W.wiki_list("undead, Demon", title=True),
                         ["Undead", "Demon"])
        self.assertEqual(W.wiki_list("undead, Demon"), ["undead", "Demon"])

    def test_empty_values_stay_none(self):
        self.assertIsNone(W.wiki_title(""))
        self.assertIsNone(W.wiki_patch(None))


class VersionedParams(unittest.TestCase):
    def test_variant_overrides_then_falls_back(self):
        p = {"name": "Base", "name2": "Variant", "examine": "shared"}
        self.assertEqual(W.versioned(p, "2")("name"), "Variant")
        self.assertEqual(W.versioned(p, "3")("name"), "Base")
        self.assertEqual(W.versioned(p, "2")("examine"), "shared")

    def test_unsuffixed_index_reads_the_bare_key(self):
        self.assertEqual(W.versioned({"name": "X"}, "")("name"), "X")

    def test_comma_separated_id_list_expands(self):
        text = ("{{Infobox Item|name=Hill Giant|id1=2098,13502|id2=2099}}")
        rows = parse_item_page("Hill Giant", text)
        self.assertEqual([r["id"] for r in rows], [2098, 13502, 2099])


class IdParsing(unittest.TestCase):
    def test_comment_digits_are_not_ids(self):
        """`110<!--Also unused IDs 116, 117-->` fabricated two extra ids."""
        self.assertEqual(parse_ids("110<!--Also unused IDs 116, 117-->"), [110])
        self.assertEqual(parse_ids("2830<!--Crack the Clue III 12345-->"), [2830])

    def test_historical_and_beta_ids_are_skipped(self):
        """`hist1` on a removed NPC produced id 1, which is a live monster."""
        self.assertEqual(parse_ids("hist1"), [])
        self.assertEqual(parse_ids("hist11249"), [])
        self.assertEqual(parse_ids("beta25484"), [])

    def test_override_ids_are_live(self):
        self.assertEqual(parse_ids("override12589"), [12589])

    def test_comma_list_and_trailing_comment(self):
        self.assertEqual(parse_ids("10529,29103,29104 <!-- standard -->"),
                         [10529, 29103, 29104])

    def test_mixed_live_and_historical(self):
        self.assertEqual(parse_ids("11947,hist3272"), [11947])

    def test_comma_list_is_not_one_huge_number(self):
        """wiki_int strips commas for "2,230" and welded 8255,8256,8257."""
        self.assertEqual(parse_ids("8255,8256,8257"), [8255, 8256, 8257])
        self.assertEqual(W.wiki_int("2,230"), 2230)   # still right for numbers

    def test_non_ids_yield_nothing(self):
        for raw in ("removed", "No", "N/A", "undefined", "", None):
            self.assertEqual(parse_ids(raw), [])


class Coordinates(unittest.TestCase):
    def test_map_named_pair_and_positional_points(self):
        raw = "{{Map|x=2224|y=3803|plane=1|r=5|mtype=square}}"
        pts = W.parse_map_features(raw)
        self.assertEqual(pts, [{"x": 2224, "y": 3803, "plane": 1,
                                "mtype": "square", "radius": 5}])

    def test_map_dot_list_yields_every_point(self):
        raw = "{{Map|mtype=dot|1843,3728|1847,3728|1851,3728}}"
        self.assertEqual(len(W.parse_map_features(raw)), 3)

    def test_npc_map_alias_is_matched(self):
        """{{NPC map}} redirects to {{Map}}; missing it lost 50 pages."""
        raw = "{{NPC map|x=2444|y=4431|mapID=28|r=3|mtype=square}}"
        pts = W.parse_map_features(raw)
        self.assertEqual(len(pts), 1)
        self.assertEqual(pts[0]["mapID"], 28)

    def test_duplicate_points_collapse(self):
        raw = "{{Map|mtype=dot|100,200|100,200}}"
        self.assertEqual(len(W.parse_map_features(raw)), 1)

    def test_plane_defaults_to_zero(self):
        pts = W.parse_map_features("{{Map|x=100|y=200}}")
        self.assertEqual(pts[0]["plane"], 0)

    def test_named_and_positional_coords_are_validated_differently(self):
        """Named x=/y= are trusted; positionals must look like coordinates.

        A positional argument is only a coordinate if it reads like one --
        `mtype=dot` and `group=pins` sit in the same argument list -- so those
        require 2-5 digits. A named `x=` was written as a coordinate on
        purpose, so it is taken at face value.
        """
        self.assertEqual(len(W.parse_map_features("{{Map|x=1|y=2}}")), 1)
        self.assertEqual(W.parse_map_features("{{Map|mtype=dot|1,2}}"), [])

    def test_item_spawn_coordinate_annotations(self):
        raw = ("{{ItemSpawnLine|name=Coins|location=X"
               "|100,200|101,200,qty:3|102,200,qty:1-3"
               "|103,200,qty:5,respawn:25|104,200,respawn:30|105, 200}}")
        rows = W.parse_item_spawns(raw)
        pts = rows[0]["spawns"]
        self.assertEqual(len(pts), 6)
        self.assertNotIn("quantity", pts[0])
        self.assertEqual(pts[1]["quantity"], 3)
        self.assertEqual(pts[2]["quantity"], "1-3")   # range stays a string
        self.assertEqual(pts[3]["respawnTime"], 25)
        self.assertEqual(pts[4]["respawnTime"], 30)
        self.assertEqual(pts[5]["x"], 105)            # tolerates "105, 200"

    def test_loc_line_and_object_loc_line_share_a_reader(self):
        raw = ("{{LocLine|name=N|location=[[Taverley Dungeon]]|plane=1"
               "|x:2897,y:9797|x:2899,y:9802|mtype=pin}}")
        rows = W.parse_loc_lines(raw)
        self.assertEqual(rows[0]["location"], "Taverley Dungeon")
        self.assertEqual(len(rows[0]["spawns"]), 2)
        self.assertEqual(rows[0]["spawns"][0]["plane"], 1)

        obj = raw.replace("LocLine", "ObjectLocLine")
        self.assertEqual(len(W.parse_loc_lines(obj, "ObjectLocLine")), 1)


class Recipes(unittest.TestCase):
    def setUp(self):
        self.lookup = lookup_from([(2347, "Hammer"), (2353, "Steel bar"),
                                   (1119, "Steel platebody"), (995, "Coins")])

    def test_facilities_is_plural_on_the_wiki(self):
        """Reading `facility` gave null for every one of 170 shops."""
        text = ("{{Recipe|skill1=Smithing|skill1lvl=48|skill1exp=187.5"
                "|facilities=Anvil|tools=Saw, Hammer|ticks=5"
                "|mat1=Steel bar|mat1quantity=5|output1=Steel platebody}}")
        r = parse_recipe_page("Steel platebody", text, self.lookup)[0]
        self.assertEqual(r["facility"], "Anvil")

    def test_tools_are_comma_separated(self):
        text = ("{{Recipe|tools=Saw, Hammer|output1=Steel platebody}}")
        r = parse_recipe_page("p", text, self.lookup)[0]
        self.assertIn(2347, r["toolIds"])

    def test_zero_ticks_is_preserved(self):
        text = "{{Recipe|ticks=0|output1=Steel platebody}}"
        self.assertEqual(parse_recipe_page("p", text, self.lookup)[0]["ticks"], 0)

    def test_non_numeric_ticks_is_null(self):
        text = "{{Recipe|ticks=Varies|output1=Steel platebody}}"
        self.assertIsNone(parse_recipe_page("p", text, self.lookup)[0]["ticks"])

    def test_unnumbered_skill_shorthand(self):
        text = ("{{Recipe|skill=Smithing|skilllvl=48|skillexp=10"
                "|output1=Steel platebody}}")
        r = parse_recipe_page("p", text, self.lookup)[0]
        self.assertEqual(r["skills"], [{"name": "Smithing", "lvl": 48,
                                        "xp": 10.0, "boostable": True}])

    def test_unresolvable_material_keeps_its_name(self):
        """Upstream emits id:0 here and loses the name."""
        text = "{{Recipe|mat1=Mahogany plank|output1=Banner easel}}"
        r = parse_recipe_page("p", text, self.lookup)[0]
        self.assertEqual(r["outputs"][0]["name"], "Banner easel")
        self.assertNotIn("id", r["outputs"][0])

    def test_recipe_without_outputs_is_dropped(self):
        self.assertEqual(parse_recipe_page("p", "{{Recipe|mat1=Coal}}",
                                           self.lookup), [])

    def test_identity_ignores_page_and_ordering(self):
        a = {"inputs": [{"id": 1}, {"id": 2}], "outputs": [{"id": 3}],
             "skills": [], "ticks": 2, "facility": "Anvil", "members": True,
             "toolIds": [9, 8]}
        b = {"inputs": [{"id": 2}, {"id": 1}], "outputs": [{"id": 3}],
             "skills": [], "ticks": 2, "facility": "Anvil", "members": True,
             "toolIds": [8, 9]}
        self.assertEqual(_recipe_identity(a), _recipe_identity(b))


class ItemLookup(unittest.TestCase):
    def test_coins_pins_to_the_canonical_id(self):
        """Two pages are named Coins; 617 sorted first and won."""
        lk = lookup_from([(617, "Coins"), (995, "Coins")])
        self.assertEqual(lk("Coins"), CANONICAL_IDS["coins"])

    def test_section_anchor_is_stripped(self):
        lk = lookup_from([(5509, "Bird nest (egg)")])
        self.assertEqual(lk("Bird nest (egg)#Red egg"), 5509)

    def test_variant_suffix_falls_back(self):
        lk = lookup_from([(1, "Prayer potion")])
        self.assertEqual(lk("Prayer potion(3)"), 1)

    def test_unknown_name_is_none(self):
        self.assertIsNone(lookup_from([(1, "A")])("nothing here"))


class Drops(unittest.TestCase):
    def test_variants_are_tagged_by_source(self):
        text = ("{{DropsLine|name=Bones|quantity=1|rarity=Always}}"
                "{{DropsLineSkill|name=Potato seed|rarity=1/5.65|skill=Thieving}}"
                "{{DropsLineReward|name=Raw shrimps|rarity=Varies}}")
        lk = lookup_from([(526, "Bones"), (5318, "Potato seed"),
                          (317, "Raw shrimps")])
        got = {d["name"]: d for d in W.parse_drops(text, lk)}
        self.assertNotIn("source", got["Bones"])
        self.assertEqual(got["Potato seed"]["source"], "skill")
        self.assertEqual(got["Raw shrimps"]["source"], "reward")

    def test_skill_case_is_normalised(self):
        """Editors write both Thieving and thieving."""
        text = "{{DropsLineSkill|name=X|skill=thieving}}"
        self.assertEqual(W.parse_drops(text, None)[0]["skill"], "Thieving")

    def test_rarity_and_quantity_stay_raw(self):
        text = "{{DropsLine|name=X|quantity=25-35|rarity=Always|rolls=2}}"
        d = W.parse_drops(text, None)[0]
        self.assertEqual(d["quantity"], "25-35")
        self.assertEqual(d["rarity"], "Always")
        self.assertEqual(d["rolls"], 2)

    def test_shared_tables_record_their_rate(self):
        text = ("{{RareDropTable|1/128|11/128|naturetalisman=yes}}"
                "{{HerbDropLines|46/128|1-2}}")
        got = {t["table"]: t for t in W.parse_drop_tables(text)}
        self.assertEqual(got["rare"]["rarity"], "1/128")
        self.assertEqual(got["rare"]["megaRarity"], "11/128")
        self.assertTrue(got["rare"]["naturetalisman"])
        # the second positional means quantity for herbs, not a second rate
        self.assertEqual(got["herb"]["quantity"], "1-2")


class SkillInfo(unittest.TestCase):
    def test_farming_seed_to_crop_edge(self):
        """The link that connects a seed to what it grows."""
        text = ("{{Farming info|name=Snape grass plant|level=61"
                "|patch=[[Allotment patch]]|seed={{plink|Snape grass seed}}"
                "|crop={{plink|Snape grass}}|seedsper=3|plantxp=82"
                "|harvestxp=82|payment=5 {{plink|jangerberries}}}}")
        e = W.parse_skill_info(text)[0]
        self.assertEqual(e["skill"], "Farming")
        self.assertEqual(e["seed"], "Snape grass seed")
        self.assertEqual(e["crop"], "Snape grass")
        self.assertEqual(e["level"], 61)
        self.assertEqual(e["payment"], "5 jangerberries")

    def test_fractional_xp_is_kept(self):
        text = "{{Woodcutting info|name=Oak|level=15|xp=37.5|tool=[[Axe]]}}"
        self.assertEqual(W.parse_skill_info(text)[0]["xp"], 37.5)

    def test_versioned_blocks_split(self):
        text = "{{Mining info|name=R|level1=15|xp1=35|level2=30|xp2=80}}"
        got = W.parse_skill_info(text)
        self.assertEqual(sorted(e["level"] for e in got), [15, 30])

    def test_block_without_level_or_xp_is_ignored(self):
        self.assertEqual(W.parse_skill_info("{{Mining info|name=R}}"), [])

    def test_secondary_skill_crossed_with_variant(self):
        """Barbarian Fishing trains Fishing and Strength at once.

        The variants here are signalled only by `version1`/`version2` and the
        suffix on `skill1lvl2` -- there is no plain `level` or `xp` param, so
        keying variant detection off those alone found just one block.
        """
        text = ("{{Fishing info|version1=Harpoon|version2=Bare-handed"
                "|name=Raw tuna|skill1lvl1=35|skill1lvl2=55|skill1exp=80"
                "|skill2name2=Strength|skill2lvl2=35|skill2exp2=8"
                "|skill1note2=Requires [[Barbarian Fishing]] training.}}")
        got = W.parse_skill_info(text)
        self.assertEqual(len(got), 2)
        harpoon, bare = got
        self.assertEqual(harpoon["level"], 35)
        self.assertEqual([s["skill"] for s in harpoon["skills"]], ["Fishing"])
        self.assertEqual(bare["level"], 55)
        strength = [s for s in bare["skills"] if s["skill"] == "Strength"][0]
        self.assertEqual((strength["level"], strength["xp"]), (35, 8))
        self.assertIn("Barbarian Fishing", bare["skills"][0]["note"])

    def test_negative_experience_is_a_hidden_value(self):
        """`skill2exp1 = -1` hides the number rather than stating -1 xp."""
        text = ("{{Fishing info|version1=A|name=X|skill1lvl1=10"
                "|skill2name1=Strength|skill2exp1=-1}}")
        got = W.parse_skill_info(text)[0]
        strength = [s for s in got["skills"] if s["skill"] == "Strength"]
        self.assertTrue(all("xp" not in s for s in strength))


class RelativeLocations(unittest.TestCase):
    def test_adjacency_is_read(self):
        text = ("{{Relativelocation|location=Meiyerditch|north=Darkmeyer"
                "|west=Burgh de Rott|south=Mos Le'Harmless|east=Slepe}}")
        self.assertEqual(W.parse_relative_locations(text), {
            "location": "Meiyerditch", "north": "Darkmeyer",
            "south": "Mos Le'Harmless", "east": "Slepe",
            "west": "Burgh de Rott"})

    def test_missing_sides_are_omitted_not_nulled(self):
        got = W.parse_relative_locations(
            "{{Relativelocation|location=Edge|north=A}}")
        self.assertEqual(set(got), {"location", "north"})

    def test_absent_template_is_none(self):
        self.assertIsNone(W.parse_relative_locations("nothing"))


class Agility(unittest.TestCase):
    def test_agility_obstacle_is_a_skill_info_block(self):
        text = ("{{Agility info|name=Agility Pyramid Completion Bonus"
                "|level=30|xp=Varies|course=[[Agility Pyramid]]"
                "|type=Completion Bonus}}")
        e = W.parse_skill_info(text)[0]
        self.assertEqual(e["skill"], "Agility")
        self.assertEqual(e["level"], 30)
        self.assertEqual(e["course"], "Agility Pyramid")
        # "Varies" is not a number and must not become 0
        self.assertNotIn("xp", e)


class Shops(unittest.TestCase):
    def test_multiplier_is_per_mille(self):
        self.assertEqual(_percent("1000"), 1.0)
        self.assertEqual(_percent("600"), 0.6)
        self.assertIsNone(_percent(None))

    def test_hidebuy_means_the_shop_does_not_buy(self):
        """A default of 0.6 was invented for 294 shops that buy nothing."""
        self.assertEqual(_buy_percent({"hidebuy": "y"}), 0.0)
        self.assertEqual(_buy_percent({"buymultiplier": "0"}), 0.0)
        self.assertEqual(_buy_percent({"buymultiplier": "600"}), 0.6)
        self.assertIsNone(_buy_percent({}))

    def test_proximity_picks_the_owner_inside_the_shop(self):
        """Three Varrock bartenders share a name; coordinates separate them."""
        shop = [{"x": 3216, "y": 3397, "plane": 0}]
        cands = [
            {"id": 1310, "spawns": [{"x": 3277, "y": 3489, "plane": 0}]},
            {"id": 1311, "spawns": [{"x": 3268, "y": 3391, "plane": 0}]},
            {"id": 1312, "spawns": [{"x": 3226, "y": 3398, "plane": 0}]},
        ]
        got = _narrow_by_proximity(cands, shop)
        self.assertEqual([c["id"] for c in got], [1312])

    def test_proximity_declines_when_nothing_is_close(self):
        shop = [{"x": 0, "y": 0, "plane": 0}]
        cands = [{"id": 1, "spawns": [{"x": 9000, "y": 9000, "plane": 0}]},
                 {"id": 2, "spawns": [{"x": 8000, "y": 8000, "plane": 0}]}]
        self.assertEqual(len(_narrow_by_proximity(cands, shop)), 2)

    def test_proximity_ignores_a_different_plane(self):
        shop = [{"x": 100, "y": 100, "plane": 0}]
        cands = [{"id": 1, "spawns": [{"x": 100, "y": 100, "plane": 1}]},
                 {"id": 2, "spawns": [{"x": 105, "y": 105, "plane": 0}]}]
        self.assertEqual([c["id"] for c in _narrow_by_proximity(cands, shop)], [2])


def shop(name, page, owners, prices, inventory=(("1", 5, 100),), **extra):
    sell, buy, delta = prices
    return {"name": name, "page": page, "index": 0, "ownerNpcIds": list(owners),
            "sellPercent": sell, "buyPercent": buy, "buyChangePercent": delta,
            "location": extra.get("location"), "specialty": extra.get("specialty"),
            "ownerAmbiguous": extra.get("ambiguous"),
            "inventory": [{"id": int(i), "name": str(i), "baseQuantity": q,
                           "restockTime": r} for i, q, r in inventory]}


class ShopMerge(unittest.TestCase):
    def setUp(self):
        # merge_shops reports its group counts; keep the test output clean
        self._stderr, sys.stderr = sys.stderr, io.StringIO()

    def tearDown(self):
        sys.stderr = self._stderr

    def test_null_is_compatible_with_any_value(self):
        a = shop("A", "A", [1], (1.0, 0.0, 0.02))
        b = shop("B", "B", [1], (None, 0.0, None))
        self.assertTrue(_prices_compatible(a, b))

    def test_two_stated_values_conflict(self):
        a = shop("A", "A", [1], (1.2, 0.9, 0.02))
        b = shop("B", "B", [1], (1.1, 1.0, 0.02))
        self.assertFalse(_prices_compatible(a, b))

    def test_page_pair_merges_and_fills_gaps(self):
        """Blue Moon Inn: the shop page omits sellPercent, the NPC page states it."""
        got = merge_shops([
            shop("Bartender (Blue Moon Inn)", "Bartender (Blue Moon Inn)",
                 [1312], (1.0, 0.0, None)),
            shop("Blue Moon Inn", "Blue Moon Inn", [1312], (None, 0.0, None),
                 location="Varrock"),
        ])
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["sellPercent"], 1.0)
        self.assertEqual(got[0]["location"], "Varrock")
        self.assertEqual(sorted(got[0]["pages"]),
                         ["Bartender (Blue Moon Inn)", "Blue Moon Inn"])

    def test_price_variants_stay_separate(self):
        """Davon's shop has two genuine price tiers; collapsing loses one."""
        got = merge_shops([
            shop("Davon", "Davon", [1], (1.2, 0.9, 0.02)),
            shop("Davon's Amulet Store.", "Davon's Amulet Store.", [1],
                 (1.2, 0.9, 0.02), location="Brimhaven"),
            shop("Davon", "Davon", [1], (1.1, 1.0, 0.02)),
            shop("Davon's Amulet Store.", "Davon's Amulet Store.", [1],
                 (1.1, 1.0, 0.02), location="Brimhaven"),
        ])
        self.assertEqual(len(got), 2)
        self.assertEqual(sorted(s["buyPercent"] for s in got), [0.9, 1.0])

    def test_ambiguous_owner_never_merges(self):
        got = merge_shops([
            shop("A", "A", [1], (1.0, 0.6, 0.02), ambiguous=True),
            shop("B", "B", [1], (1.0, 0.6, 0.02), ambiguous=True),
        ])
        self.assertEqual(len(got), 2)

    def test_ownerless_shop_never_merges(self):
        got = merge_shops([shop("A", "A", [], (1.0, 0.6, 0.02)),
                           shop("B", "B", [], (1.0, 0.6, 0.02))])
        self.assertEqual(len(got), 2)

    def test_different_inventory_never_merges(self):
        got = merge_shops([
            shop("A", "A", [1], (1.0, 0.6, 0.02), inventory=(("1", 5, 100),)),
            shop("B", "B", [1], (1.0, 0.6, 0.02), inventory=(("2", 5, 100),)),
        ])
        self.assertEqual(len(got), 2)

    def test_index_is_renumbered_per_page(self):
        got = merge_shops([shop("A", "P", [1], (1.0, 0.6, 0.02)),
                           shop("B", "P", [2], (1.0, 0.6, 0.02),
                                inventory=(("9", 1, 1),))])
        self.assertEqual(sorted(s["index"] for s in got), [0, 1])


class CacheJoin(unittest.TestCase):
    def wiki(self, obj_id=1, **extra):
        return [{"objectId": obj_id, "name": "Iron rocks", "page": "Iron rocks",
                 "actions": ["Mine"], "produce": [{"name": "Iron ore"}],
                 "skillInfo": [{"skill": "Mining"}], "locations": [],
                 "spawns": [], **extra}]

    def test_overlapping_object_keeps_both_sides(self):
        got = join_cache(self.wiki(), {1: {"name": "Iron rocks",
                                           "actions": ["Mine"],
                                           "blockingMask": 3, "sizeX": 1}})
        self.assertEqual(len(got), 1)
        rec = got[0]
        self.assertEqual(rec["source"], "both")
        self.assertEqual(rec["produce"], [{"name": "Iron ore"}])   # wiki
        self.assertEqual(rec["blockingMask"], 3)                   # cache

    def test_cache_actions_win_and_wiki_actions_are_kept(self):
        got = join_cache(self.wiki(), {1: {"name": "X",
                                           "actions": ["Mine", "Prospect"]}})
        self.assertEqual(got[0]["actions"], ["Mine", "Prospect"])
        self.assertEqual(got[0]["wikiActions"], ["Mine"])

    def test_interactable_cache_only_object_is_emitted(self):
        got = join_cache(self.wiki(), {2: {"name": "Bank booth",
                                           "actions": ["Bank"],
                                           "blockingMask": 1}})
        by_id = {r["objectId"]: r for r in got}
        self.assertEqual(by_id[2]["source"], "cache")
        self.assertIsNone(by_id[2]["page"])
        self.assertEqual(by_id[2]["produce"], [])

    def test_inert_cache_only_object_is_dropped(self):
        """Named but action-less scenery -- walls, water -- is dead weight."""
        got = join_cache(self.wiki(), {2: {"name": "Wall", "actions": [],
                                           "blockingMask": 1}})
        self.assertEqual([r["objectId"] for r in got], [1])

    def test_inert_object_still_enriches_a_wiki_record(self):
        """Dropping applies to cache-only rows, not to described objects."""
        got = join_cache(self.wiki(obj_id=5),
                         {5: {"name": "Iron rocks", "actions": [],
                              "blockingMask": 7}})
        self.assertEqual(got[0]["blockingMask"], 7)
        self.assertEqual(got[0]["source"], "both")

    def test_wiki_only_object_survives(self):
        got = join_cache(self.wiki(obj_id=7), {})
        self.assertEqual(got[0]["source"], "wiki")
        self.assertEqual(got[0]["objectId"], 7)

    def test_missing_dump_directory_is_empty_not_fatal(self):
        self.assertEqual(load_cache_objects("/nonexistent/path/objects"), {})

    def test_unnamed_actionless_objects_are_skipped(self):
        """Half the dump is decoration a consumer can never act on."""
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "1.json").write_text(json.dumps(
                {"id": 1, "name": "null", "ops": {"ops": []}}))
            (d / "2.json").write_text(json.dumps(
                {"id": 2, "name": "Bank booth",
                 "ops": {"ops": [{"text": "Bank"}]}, "varbitID": -1}))
            got = load_cache_objects(d)
        self.assertEqual(list(got), [2])
        # -1 is the cache's "unset" marker, not a real varbit
        self.assertNotIn("varbitId", got[2])

    def test_non_json_files_in_the_dump_are_ignored(self):
        """ObjectDumper also writes ObjectID.java beside the JSON."""
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "ObjectID.java").write_text("public class ObjectID {}")
            (d / "3.json").write_text(json.dumps(
                {"id": 3, "name": "Tree", "ops": {"ops": []}}))
            self.assertEqual(list(load_cache_objects(d)), [3])


class Magic(unittest.TestCase):
    def test_rune_names_resolve_to_items(self):
        lk = lookup_from([(561, "Nature rune"), (554, "Fire rune")])
        got = parse_rune_cost("{{RuneReq|Nature=1|Fire=5}}", lk)
        by = {r["rune"]: r for r in got}
        self.assertEqual(by["Nature"]["id"], 561)
        self.assertEqual(by["Fire"]["quantity"], 5)

    def test_missing_cost_is_empty(self):
        self.assertEqual(parse_rune_cost(None, None), [])


class Quests(unittest.TestCase):
    def test_scp_calls_are_extracted_from_prose(self):
        raw = ("* {{SCP|Quest|32}} [[Quest points]]\n"
               "* The ability to defeat a level 83 [[Elvarg|dragon]]\n"
               "* {{SCP|Crafting|8}} [[Crafting]]")
        self.assertEqual(parse_scp(raw),
                         [{"skill": "Quest", "value": 32},
                          {"skill": "Crafting", "value": 8}])

    def test_xp_reward_commas_are_handled(self):
        self.assertEqual(parse_scp("{{SCP|Strength|18,650}}")[0]["value"], 18650)

    def test_bullets_keep_prose_requirements(self):
        raw = "* {{SCP|Quest|32}} [[Quest points]]\n* Defeat a level 83 dragon"
        self.assertEqual(_bullets(raw),
                         ["Quest points", "Defeat a level 83 dragon"])


class CompactShape(unittest.TestCase):
    def make(self):
        return [
            {"id": 1, "page": "P", "drops": [{"a": 1}], "hp": 10},
            {"id": 2, "page": "P", "drops": [{"a": 1}], "hp": 20},
            {"id": 3, "page": "Q", "drops": [{"b": 2}], "hp": 30},
        ]

    def test_shared_field_is_hoisted(self):
        recs, pages = W.compact_by_page(self.make(), ("drops",))
        self.assertIn("P", pages)
        self.assertNotIn("drops", recs[0])
        self.assertEqual(recs[0]["hp"], 10)

    def test_varying_field_stays_inline(self):
        rows = self.make()
        rows[1]["drops"] = [{"a": 999}]
        recs, pages = W.compact_by_page(rows, ("drops",))
        self.assertNotIn("P", pages)
        self.assertTrue(all("drops" in r for r in recs[:2]))

    def test_round_trip_is_exact(self):
        rows = self.make()
        recs, pages = W.compact_by_page(rows, ("drops",))
        back = W.rehydrate_by_page({"pages": pages, "records": recs})
        self.assertEqual(json.dumps(rows, sort_keys=True),
                         json.dumps(back, sort_keys=True))

    def test_record_order_is_preserved(self):
        """Grouping by page silently reordered the output."""
        rows = [{"id": 1, "page": "A", "x": [1]}, {"id": 2, "page": "B", "x": [2]},
                {"id": 3, "page": "A", "x": [1]}]
        recs, _ = W.compact_by_page(rows, ("x",))
        self.assertEqual([r["id"] for r in recs], [1, 2, 3])

    def test_single_record_page_still_hoists(self):
        recs, pages = W.compact_by_page(
            [{"id": 1, "page": "Solo", "x": [1]}], ("x",))
        self.assertIn("Solo", pages)

    def test_empty_values_are_left_alone(self):
        recs, pages = W.compact_by_page(
            [{"id": 1, "page": "P", "x": []}], ("x",))
        self.assertEqual(pages, {})
        self.assertEqual(recs[0]["x"], [])

    def test_records_without_a_page_are_untouched(self):
        recs, pages = W.compact_by_page([{"id": 1, "x": [1]}], ("x",))
        self.assertEqual(pages, {})
        self.assertEqual(recs[0]["x"], [1])


class CombatStyles(unittest.TestCase):
    def test_style_name_comes_from_the_first_positional(self):
        got = parse_combat_styles("{{CombatStyles|Whip|speed=4|attackrange=1}}")
        self.assertEqual(got, {"style": "Whip", "attackSpeed": 4,
                               "attackRange": 1})

    def test_style_without_overrides(self):
        self.assertEqual(parse_combat_styles("{{CombatStyles|Thrown}}"),
                         {"style": "Thrown"})

    def test_absent_template_is_none(self):
        self.assertIsNone(parse_combat_styles("no styles here"))



class InertScenery(unittest.TestCase):
    """Dropping objects nothing can interact with."""

    def test_actionable_objects_are_kept(self):
        kept, dropped = drop_inert([{"name": "Door", "actions": ["Open"]}])
        self.assertEqual(len(kept), 1)
        self.assertEqual(dropped, [])

    def test_action_less_decoration_is_dropped(self):
        kept, dropped = drop_inert([{"name": "Fence", "actions": []}])
        self.assertEqual(kept, [])
        self.assertEqual(len(dropped), 1)

    def test_wiki_actions_count_as_actions(self):
        kept, _ = drop_inert([{"name": "Gate", "actions": [],
                               "wikiActions": ["Open"]}])
        self.assertEqual(len(kept), 1)

    def test_gathering_sources_survive_without_actions(self):
        # Soil has no right-click on the record but yields charcoal through
        # Mining; dropping it loses gathering data, not decoration
        soil = {"name": "Soil", "actions": [],
                "produce": [{"name": "Charcoal"}],
                "skillInfo": [{"skill": "Mining"}]}
        kept, dropped = drop_inert([soil])
        self.assertEqual(len(kept), 1)
        self.assertEqual(dropped, [])

    def test_strict_drops_gathering_sources_too(self):
        soil = {"name": "Soil", "actions": [], "produce": [{"name": "Coal"}]}
        kept, dropped = drop_inert([soil], strict=True)
        self.assertEqual(kept, [])
        self.assertEqual(len(dropped), 1)

    def test_nothing_is_lost_between_the_two_lists(self):
        rows = [{"actions": ["a"]}, {"actions": []},
                {"actions": [], "produce": [1]}]
        kept, dropped = drop_inert(rows)
        self.assertEqual(len(kept) + len(dropped), len(rows))


class IdJoins(unittest.TestCase):
    """Joining monsters to npcs on id, where a handful of ids disagree."""

    def test_matching_names_join(self):
        joined, dropped = W.join_by_id(
            [{"id": 1, "name": "Goblin", "hp": 5}],
            [{"id": 1, "name": "Goblin", "spawns": 3}])
        self.assertEqual(dropped, set())
        self.assertEqual(joined[1], {"id": 1, "name": "Goblin",
                                     "hp": 5, "spawns": 3})

    def test_disagreeing_names_are_dropped_not_guessed(self):
        # id 108 is White wolf on one side and Monk on the other
        joined, dropped = W.join_by_id(
            [{"id": 108, "name": "White wolf"}],
            [{"id": 108, "name": "Monk"}])
        self.assertEqual(dropped, {108})
        self.assertNotIn(108, joined)

    def test_case_difference_alone_is_not_a_collision(self):
        # 'Giant Lobster' vs 'Giant lobster' is one entity, not two
        joined, dropped = W.join_by_id(
            [{"id": 4799, "name": "Giant Lobster"}],
            [{"id": 4799, "name": "Giant lobster"}])
        self.assertEqual(dropped, set())
        self.assertIn(4799, joined)

    def test_one_bad_id_does_not_drop_the_rest(self):
        joined, dropped = W.join_by_id(
            [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}],
            [{"id": 1, "name": "A"}, {"id": 2, "name": "Other"}])
        self.assertEqual(dropped, {2})
        self.assertEqual(set(joined), {1})

    def test_records_without_an_id_are_ignored(self):
        joined, dropped = W.join_by_id(
            [{"name": "no id"}, {"id": 5, "name": "X"}],
            [{"name": "no id"}, {"id": 5, "name": "X"}])
        self.assertEqual(set(joined), {5})
        self.assertEqual(dropped, set())

    def test_real_datasets_drop_only_the_known_collisions(self):
        data = Path(__file__).resolve().parent.parent / "data" / "wiki"
        if not (data / "all-monsters.json").exists():
            self.skipTest("datasets not built")
        monsters = W.load_dataset(data / "all-monsters.json")
        npcs = W.load_dataset(data / "all-npcs.json")
        joined, dropped = W.join_by_id(monsters, npcs)
        self.assertLess(len(dropped), 20, "a join should not drop wholesale")
        self.assertTrue(joined, "the join produced nothing")
        for ident in dropped:
            self.assertNotIn(ident, joined)


class ApiErrors(unittest.TestCase):
    """The retry path, which every builder depends on and nothing exercised.

    MediaWiki reports most failures as HTTP 200 with an `error` object, so
    these cases are about what happens *after* a successful request.
    """

    def setUp(self):
        self.slept = []
        self._urlopen = W.urllib.request.urlopen
        self._sleep = W.time.sleep
        W.time.sleep = self.slept.append

    def tearDown(self):
        W.urllib.request.urlopen = self._urlopen
        W.time.sleep = self._sleep

    def fake(self, *payloads):
        """Serve each payload in turn; an Exception instance is raised."""
        seq = list(payloads)
        self.calls = 0

        class Response(io.StringIO):
            def __enter__(self): return self
            def __exit__(self, *a): return False

        def urlopen(request, timeout=None):
            self.calls += 1
            item = seq.pop(0) if len(seq) > 1 else seq[0]
            if isinstance(item, Exception):
                raise item
            return Response(json.dumps(item))

        W.urllib.request.urlopen = urlopen

    def test_success_returns_the_body(self):
        self.fake({"query": {"pages": []}})
        self.assertEqual(W.api_get({"action": "query"}), {"query": {"pages": []}})
        self.assertEqual(self.calls, 1)

    def test_api_error_raises_rather_than_returning_a_bodyless_dict(self):
        # the bug: this came back as data, and callers using .get("query", {})
        # saw an empty result and carried on
        self.fake({"error": {"code": "missingtitle", "info": "no such page"}})
        with self.assertRaises(W.WikiError) as caught:
            W.api_get({"action": "parse", "page": "Nope"})
        self.assertEqual(caught.exception.code, "missingtitle")

    def test_permanent_api_error_is_not_retried(self):
        self.fake({"error": {"code": "missingtitle", "info": "x"}})
        with self.assertRaises(W.WikiError):
            W.api_get({"action": "parse"})
        self.assertEqual(self.calls, 1, "a missing page will not appear later")
        self.assertEqual(self.slept, [])

    def test_rate_limit_is_retried_then_succeeds(self):
        self.fake({"error": {"code": "ratelimited", "info": "slow down"}},
                  {"query": {"ok": 1}})
        self.assertEqual(W.api_get({"action": "query"}), {"query": {"ok": 1}})
        self.assertEqual(self.calls, 2)
        self.assertEqual(self.slept, [1], "should back off before retrying")

    def test_rate_limit_gives_up_loudly_after_retries(self):
        self.fake({"error": {"code": "ratelimited", "info": "slow down"}})
        with self.assertRaises(W.WikiError):
            W.api_get({"action": "query"}, retries=3)
        self.assertEqual(self.calls, 3)
        self.assertEqual(self.slept, [1, 2], "exponential backoff between tries")

    def test_transport_error_is_retried(self):
        self.fake(TimeoutError("timed out"), {"query": {"ok": 1}})
        self.assertEqual(W.api_get({"action": "query"}), {"query": {"ok": 1}})
        self.assertEqual(self.calls, 2)

    def test_transport_error_raises_after_the_last_try(self):
        self.fake(TimeoutError("timed out"))
        with self.assertRaises(TimeoutError):
            W.api_get({"action": "query"}, retries=2)
        self.assertEqual(self.calls, 2)

    def test_malformed_json_is_retried_not_returned(self):
        class Bad(io.StringIO):
            def __enter__(self): return self
            def __exit__(self, *a): return False
        seq = [Bad("not json"), Bad(json.dumps({"query": {"ok": 1}}))]
        W.urllib.request.urlopen = lambda request, timeout=None: seq.pop(0)
        self.assertEqual(W.api_get({"action": "query"}), {"query": {"ok": 1}})


if __name__ == "__main__":
    unittest.main(verbosity=2 if "-v" in sys.argv else 1)
