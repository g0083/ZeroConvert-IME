import unittest
from build_corrector_dataset import augment


class CorrectorDatasetTests(unittest.TestCase):
    def test_keyboard_augmentation_preserves_floating_letters_and_gold_source(self):
        row = {"prompt": "\uee02作業後に\uee00オツカレサマ\uee01", "completion": "お疲れ様",
               "target_reading": "おつかれさま"}
        generated = augment([row])
        self.assertEqual(generated, augment([row]))
        self.assertNotIn("augmentation", row)
        self.assertTrue(any(any(c.isascii() and c.isalpha() for c in r["input_reading"])
                            for r in generated[1:]))
        self.assertTrue(all(r["completion"] == "お疲れ様" for r in generated))
        self.assertTrue(any(r.get("augmentation") == "clean_counterpart" for r in generated))

    def test_mixed_canonical_target_is_not_invented_as_a_reading(self):
        row = {"prompt": "\uee00オ伺イシマス\uee01", "completion": "お伺いします",
               "target_reading": "お伺いします"}
        self.assertEqual(augment([row]), [row])


if __name__ == "__main__":
    unittest.main()
