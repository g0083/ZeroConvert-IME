# SPDX-License-Identifier: Apache-2.0
"""Reading/partition regressions for the scalable Origin corpus builder."""
import unittest
import random

from build_origin_corpus import NearDocuments, expand, orthographic_tokens, romanize
from rebalance_origin_repair import transform


class CorpusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import fugashi
        cls.tagger = fugashi.Tagger()

    def test_orthography_not_pronunciation(self):
        text = '今日は会議室へ行って、資料を共有します。'
        tokens = orthographic_tokens(text, self.tagger)
        self.assertEqual(''.join(s for s, _ in tokens), text)
        self.assertEqual(''.join(r for _, r in tokens), 'きょうはかいぎしつへいって、しりょうをきょうゆうします。')

    def test_width_whitespace_ascii_and_katakana_preserved(self):
        text = '  ＡＢＣ １２３ Pythonをコピーします。'
        tokens = orthographic_tokens(text, self.tagger)
        self.assertEqual(''.join(s for s, _ in tokens), text)
        reading = ''.join(r for _, r in tokens)
        self.assertIn('ＡＢＣ １２３ Pythonをこぴー', reading)

    def test_nasal_geminate_and_small_kana(self):
        for reading, expected in [('おんな', 'onnna'), ('きって', 'kitte'), ('しんよう', 'shinnyou'),
                                  ('きょう', 'kyou'), ('こぴー', 'kopi-'), ('あっ', 'axtsu')]:
            self.assertEqual(romanize(reading), expected)
        self.assertIsNone(romanize('名前'))

    def test_repair_slots_cannot_reveal_gold_action(self):
        sentence = '会議の資料を棚に戻します。'
        tokens = orthographic_tokens(sentence, self.tagger)
        rows = list(expand(sentence, '受付の仕事です。', tokens, 'test-doc', 0, {}, random.Random(42)))
        repairs = [row for row in rows if row['task'] == 'repair_qwerty']
        self.assertEqual({r['target']['action'] for r in repairs}, {'keep', 'emit', 'abstain'})
        for row in repairs:
            self.assertEqual(set(row['input']), {'ctx', 'raw', 'pending'})
            self.assertNotIn(row['reference_reading'], row['input'].values())

    def test_rebalance_preserves_nonrepair_and_keeps_reference_outside_input(self):
        row = {'task': 'convert', 'input': {'reading': 'かいぎ'}}
        self.assertIs(transform(row), row)
        normal = {'task': 'repair_qwerty', 'input': {'raw': 'kaigi', 'surface': 'かいぎ', 'cursor': 3},
                  'target': {'action': 'keep', 'text': ''}}
        fixed = transform(normal)
        self.assertEqual(fixed['reference_reading'], 'かいぎ')
        self.assertEqual(fixed['input'], {'ctx': '', 'raw': 'kaigi', 'pending': ''})
        self.assertEqual(normal['input']['surface'], 'かいぎ')

    def test_near_duplicates_do_not_insert_when_querying_holdout(self):
        index = NearDocuments()
        text = '会議室の棚に保管した資料を確認してから、受付の担当者に連絡してください。'
        self.assertFalse(index.duplicate(text))
        # One terminal-character edit is above the configured .85 Jaccard gate.
        self.assertTrue(index.duplicate(text[:-1] + '！', remember=False))
        count = len(index.texts)
        self.assertFalse(index.duplicate('これは別の物語で、庭の植物に水を与える場面です。', remember=False))
        self.assertEqual(len(index.texts), count)


if __name__ == '__main__':
    unittest.main()
