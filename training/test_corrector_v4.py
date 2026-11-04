"""Regression checks for inherited labels and group-isolated selection."""
import unittest

from build_corrector_v4_dataset import filter_groups, is_clean, valid_example
from train_corrector_v4 import validation_cohort


def row(text, group="one", **kwargs):
    return {"prompt": f"\uee00{text}\uee01", "completion": "テスト", "target_reading": "てすと",
            "source_group": group, **kwargs}


class CorrectorV4Tests(unittest.TestCase):
    def test_foreign_control_markers_are_rejected_without_rewriting_answers(self):
        self.assertTrue(valid_example(row("テスト")))
        self.assertFalse(valid_example(row("テスト\uee08別タスク")))
        self.assertFalse(valid_example(row("テスト", completion="テスト\uee08注釈")))
        self.assertFalse(valid_example(row("テスト\uee00テスト")))

    def test_inherited_clean_flag_cannot_label_a_generated_typo_as_clean(self):
        self.assertFalse(is_clean(row("テソト", is_clean=True, augmentation="keyboard_adjacent")))
        self.assertTrue(is_clean(row("テスト", is_clean=False)))

    def test_reservation_removes_all_siblings(self):
        rows = [row("テソト"), row("テスオ"), row("ホゲ", "other")]
        kept, blocked = filter_groups(rows, {"てそと"})
        self.assertEqual(blocked, {"one"})
        self.assertEqual([r["source_group"] for r in kept], ["other"])

    def test_balanced_cohort_is_order_independent_and_caps_each_group(self):
        rows = [row("テスト"), row("テソト"), row("テスオ"), row("テスト", "two"), row("テソト", "two")]
        a = validation_cohort(rows, per_kind=1)
        b = validation_cohort(list(reversed(rows)), per_kind=1)
        self.assertEqual(a, b)
        self.assertEqual([r["validation_clean"] for r in a], [True, False])


if __name__ == "__main__":
    unittest.main()
