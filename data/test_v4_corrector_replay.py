# SPDX-License-Identifier: Apache-2.0
from collections import Counter
import unittest

from add_v4_corrector_replay import generate
from keyboard_hybrid import RomanDecoder, TABLE


@unittest.skipUnless(TABLE.exists(), 'Requires the pinned offline converter table')
class CorrectorReplayTests(unittest.TestCase):
    def setUp(self):
        self.decoder = RomanDecoder()
        self.seed = {'source_group': 'fixture-canonical-group', 'target_reading': 'かいぎしつ',
                     'completion': '会議室', 'prompt': '\uee02午後の\uee00カイギシツ\uee01'}

    def test_new_raw_is_observed_and_gold_never_enters_input(self):
        examples = list(generate([self.seed], 'train', self.decoder, Counter()))
        self.assertTrue(examples)
        self.assertEqual({r['target']['action'] for r in examples}, {'keep', 'emit', 'abstain'})
        for row in examples:
            self.assertEqual(set(row['input']), {'ctx', 'raw', 'pending'})
            self.assertTrue(row['input']['raw'].isascii())
            self.assertEqual(row['input_method'], 'romaji')
            if row['target']['action'] == 'keep':
                self.assertEqual(self.decoder.decode(row['input']['raw']), row['reference_reading'])
            if row['target']['action'] == 'emit':
                self.assertNotEqual(self.decoder.decode(row['input']['raw']), row['target']['text'])
                self.assertFalse(row['input']['pending'])
            if row['input']['pending']:
                self.assertEqual(row['target']['action'], 'abstain')

    def test_unavailable_legacy_keystrokes_are_not_fabricated(self):
        first = list(generate([{**self.seed, 'input_reading': 'カイkシツ'}], 'train', self.decoder, Counter()))
        second = list(generate([{**self.seed, 'input_reading': '実際の元打鍵は不明'}], 'train', self.decoder, Counter()))
        self.assertEqual([r['input'] for r in first], [r['input'] for r in second])
        self.assertTrue(all(r['raw_provenance'].startswith('new synthetic raw') for r in first))

    def test_unknown_kanji_reading_is_excluded(self):
        counts = Counter()
        self.assertEqual(list(generate([{**self.seed, 'target_reading': '会ぎしつ'}], 'train', self.decoder, counts)), [])
        self.assertEqual(counts['train_unverified_or_long_reading_excluded'], 1)


if __name__ == '__main__':
    unittest.main()
