import unittest
from collections import Counter

import select_additional_dictionary as selection


class SelectionTest(unittest.TestCase):
    def test_rank_uses_document_evidence_and_existing_reading_competition(self):
        input_rows = {
            ("ユズキ", "柚希"): (1, "proper", "core_lex", "person"),
            ("ユズキ", "柚樹"): (1, "proper", "core_lex", "person"),
            ("スマートノウギョウ", "スマート農業"): (4000, "common", "core_lex", "common"),
            ("グリーンスイソ", "グリーン水素"): (5000, "common", "core_lex", "common"),
        }
        rows = selection.ranked_candidates(
            input_rows, Counter({"柚希": 2, "柚樹": 1, "スマート農業": 2}),
            {"グリーン水素"}, Counter({"ユズキ": 12}),
        )
        self.assertEqual([row["surface"] for row in rows], ["グリーン水素", "スマート農業", "柚希"])

    def test_fill_unused_quota_and_preserve_reading_and_surface_caps(self):
        rows = [
            {"group": "person", "reading": "ユズキ", "surface": "柚希", "existingSameReading": 15},
            {"group": "person", "reading": "ユズキ", "surface": "柚樹", "existingSameReading": 15},
            {"group": "person", "reading": "ユキ", "surface": "柚希", "existingSameReading": 0},
            {"group": "proper", "reading": "ヨコハマ", "surface": "横浜", "existingSameReading": 1},
            {"group": "proper", "reading": "ヨコクラ", "surface": "横倉", "existingSameReading": 1},
            {"group": "proper", "reading": "ホンマチ", "surface": "本町", "existingSameReading": 1},
        ]
        picked, quotas, counts = selection.choose_rows(rows, 4)
        self.assertEqual(quotas, {"common": 1, "proper": 1, "person": 2})
        self.assertEqual(counts, {"proper": 3, "person": 1})
        self.assertEqual([row["surface"] for row in picked], ["柚希", "横浜", "横倉", "本町"])

    def test_reading_limit_carries_over_between_batches(self):
        rows = [
            {"group": "person", "reading": "ユズキ", "surface": "柚季", "existingSameReading": 20},
            {"group": "proper", "reading": "カナダ", "surface": "加奈陀", "existingSameReading": 4},
            {"group": "common", "reading": "ホンマチ", "surface": "本町", "existingSameReading": 0},
        ]
        picked, _, _ = selection.choose_rows(rows, 2, Counter({"ユズキ": 1, "カナダ": 3}))
        self.assertEqual([row["surface"] for row in picked], ["本町"])


if __name__ == "__main__":
    unittest.main()
