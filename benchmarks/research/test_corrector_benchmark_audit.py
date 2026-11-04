import unittest

from audit_corrector_benchmark import accepted, analyze, distance, wilson


class AuditTests(unittest.TestCase):
    def test_oracle_cannot_be_counted_as_model_accuracy(self):
        case = {"id": 1, "target_reading": "てすと", "input_reading": "テソト", "is_clean": False}
        with self.assertRaises(ValueError):
            analyze([case], {"fake_model": {"oracle": True, "cases": []}}, None)

    def test_distance_tracks_kana_normalization_and_adjacent_transposition(self):
        self.assertEqual(distance("テスト", "てすと"), 0)
        self.assertEqual(distance("てとす", "てすと"), 1)
        self.assertEqual(distance("", "あい"), 2)

    def test_wilson_does_not_describe_small_zero_error_samples_as_proof(self):
        self.assertGreater(wilson(0, 45)[1], .07)
        self.assertGreater(wilson(1, 45)[1], .11)
        self.assertIsNone(wilson(0, 0))

    def test_homophones_are_not_accepted_by_reading_only(self):
        row = {"expected": "機会", "acceptable": ["きかい"]}
        self.assertTrue(accepted(row, "きかい"))
        self.assertFalse(accepted(row, "機械"))
        self.assertFalse(accepted(row, None))


if __name__ == "__main__":
    unittest.main()
