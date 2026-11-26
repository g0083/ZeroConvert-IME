import json
import tempfile
import unittest
from zipfile import ZipFile
from collections import Counter
from pathlib import Path

import additional_dictionary as dictionary
from add_municipalities import name_pairs
from add_town_names import clean_reading, locality_key, postal_readings, reading_variants, resolve_reading


def reading_statement(value, qualifiers=None):
    statement = {"mainsnak": {"datavalue": {"value": value}}, "rank": "normal"}
    if qualifiers:
        statement["qualifiers"] = {"P5168": [{
            "datavalue": {"value": {"language": "ja", "text": qualifiers}}
        }]}
    return statement


class AdditionalDictionaryTest(unittest.TestCase):
    def test_town_readings_keep_digits_and_offer_kana_for_chome(self):
        self.assertEqual(list(reading_variants(clean_reading("おおどおりにし１１ちょうめ"))),
                         ["オオドオリニシ11チョウメ", "オオドオリニシジュウイッチョウメ"])
        self.assertEqual(clean_reading("北１条西２丁目"), "")
        self.assertEqual(list(reading_variants(clean_reading("キタ１ジョウニシ８チョウメ"))),
                         ["キタ1ジョウニシ8チョウメ", "キタイチジョウニシハッチョウメ"])
        self.assertEqual(clean_reading("グリ-ンタウン"), "グリーンタウン")
        characters = set("1チョウメ")
        self.assertTrue(dictionary.valid_word("1チョウメ", "１丁目", characters, Counter(), allow_digits=True))
        self.assertFalse(dictionary.valid_word("1チョウメ", "１丁目", characters, Counter()))

    def test_town_readings_use_only_unambiguous_local_or_postal_matches(self):
        row = {"lg_code": "011011", "machiaza_id": "0123001", "oaza_cho": "大通西",
               "oaza_cho_kana": "", "koaza": "上野", "koaza_kana": ""}
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "postal.zip"
            with ZipFile(archive, "w") as source:
                source.writestr("utf_ken_all.csv", "\n".join([
                    "01101,,,ホッカイドウ,サッポロシ,オオドオリニシ（１−１９チョウメ）,北海道,札幌市,大通西（１〜１９丁目）",
                    "01207,,,ホッカイドウ,オビヒロシ,オオトオリニシ,北海道,帯広市,大通西",
                ]))
            postal = postal_readings(archive)
        self.assertEqual(resolve_reading(row, "oaza_cho", {}, postal), ("オオドオリニシ", "japanPost"))
        local = {locality_key(row, "koaza"): {"ウエノ"}}
        self.assertEqual(resolve_reading(row, "koaza", local, postal), ("ウエノ", "addressBaseRegistryMatch"))
        local[locality_key(row, "koaza")].add("カミノ")
        self.assertEqual(resolve_reading(row, "koaza", local, postal)[0], "")
        self.assertEqual(resolve_reading(dict(row, koaza="字トイヨイ"), "koaza", local, postal),
                         ("アザトイヨイ", "literalKana"))

    def test_municipal_names_use_current_official_readings_and_include_city_wards(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "cities.zip"
            with ZipFile(archive, "w") as source:
                source.writestr("mt_city_all.csv", "\n".join([
                    "lg_code,pref,pref_kana,city,city_kana,ward,ward_kana,efct_date,ablt_date",
                    "011002,北海道,ホッカイドウ,札幌市,サッポロシ,,,1947-04-17,",
                    "011011,北海道,ホッカイドウ,札幌市,サッポロシ,中央区,チュウオウク,2026-11-26,",
                    "011029,北海道,ホッカイドウ,札幌市,サッポロシ,北区,キタク,1947-04-17,2026-11-26",
                    "011037,北海道,ホッカイドウ,札幌市,サッポロシ,東区,ヒガシク,2026-11-27,",
                ]))
            self.assertEqual(name_pairs(archive, "2026-11-26"), {
                ("ホッカイドウ", "北海道"): {"pref"},
                ("サッポロシ", "札幌市"): {"city"},
                ("チュウオウク", "中央区"): {"ward"},
                ("サッポロシチュウオウク", "札幌市中央区"): {"city+ward"},
            })

    def test_reading_and_surface_validation(self):
        excluded = Counter()
        self.assertEqual(dictionary.katakana(" がっこう "), "ガッコウ")
        self.assertEqual(dictionary.katakana("ｱｯﾌﾟﾙ"), "アップル")
        self.assertTrue(dictionary.valid_word("カナ", "仮名", set("カナ"), excluded))
        self.assertFalse(dictionary.valid_word("", "空欄", set(), excluded))
        self.assertFalse(dictionary.valid_word("カナ", "改\t行", set("カナ"), excluded))
        self.assertFalse(dictionary.valid_word("カナ", "未対応", set("カ"), excluded))
        self.assertFalse(dictionary.valid_word("カ" * 21, "長い", set("カ"), excluded))
        self.assertEqual(excluded, {"empty": 1, "unsupportedSurface": 1,
                                    "unsupportedReading": 1, "longReading": 1})

    def test_wikidata_multiple_names_require_evidence_or_qualifier(self):
        entity = {
            "labels": {"ja": {"value": "甲野次郎"}},
            "aliases": {"ja": [{"value": "甲次郎"}]},
            "claims": {"P1814": [reading_statement("こうの じろう")]},
        }
        self.assertIsNone(dictionary.wikidata_word(entity, {}))
        entity["claims"]["P1814"].append(reading_statement("こうの じろう", "甲野次郎"))
        self.assertEqual(dictionary.wikidata_word(entity, {}), ("コウノジロウ", "甲野次郎"))
        verified = {"surface": "甲次郎", "reading": "こうじろう", "evidence": "https://example.org/name"}
        self.assertEqual(dictionary.wikidata_word(entity, verified), ("コウジロウ", "甲次郎"))

    def test_sources_merge_by_pair_and_scores_respect_homophones(self):
        primary = ("カナ", "仮名")
        alternative = ("カンナ", "仮名")
        sudachi = {primary: (2, "sahen", "core_lex"), alternative: (3, "proper", "small_lex")}
        wikidata = {primary: [{"source": "wikidata", "qid": "Q42", "revision": 1}]}
        selected, counts = dictionary.choose_words(sudachi, wikidata)
        self.assertEqual(counts, {"proper": 1, "sahen": 1})
        existing = {"カナ": [dictionary.Entry("カナ", "かな", 1285, 1285, 501, -2)]}
        existing["アン"] = [dictionary.Entry("アン", "案", cid, cid, 501, -1)
                          for cid in dictionary.POS.values()]
        rows = {row.reading: (row, sources) for row, sources in dictionary.make_entries(selected, existing)}
        self.assertEqual(rows["カナ"][0].score, -3)
        self.assertEqual(rows["カナ"][0].lcid, 1283)
        self.assertEqual([source["source"] for source in rows["カナ"][1]], ["sudachi", "wikidata"])
        self.assertIn("カンナ", rows)

    def test_changed_input_cannot_overwrite_preceding_result(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            archive = path / "input.zip"
            archive.write_bytes(b"original")
            lock = path / "sources.json"
            lock.write_text(json.dumps({
                "schemaVersion": 1, "sudachi": [{"path": archive.name, "sha256": dictionary.digest(archive)}]
            }))
            archive.write_bytes(b"damaged")
            output = path / "trial"
            with self.assertRaisesRegex(ValueError, "Source hash mismatch"):
                dictionary.generate(lock, path / "builder", output)
            self.assertFalse(output.exists())
            output.mkdir()
            preserved = output / "good"
            preserved.write_bytes(b"kept")
            with self.assertRaises(FileExistsError):
                dictionary.generate(lock, path / "builder", output)
            self.assertEqual(preserved.read_bytes(), b"kept")


if __name__ == "__main__":
    unittest.main()
