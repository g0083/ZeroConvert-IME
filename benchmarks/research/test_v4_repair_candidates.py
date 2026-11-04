# SPDX-License-Identifier: Apache-2.0
import unittest

from evaluate_v4_repair_comparison import ReadingVerifier
from v4_repair_candidates import edits, TokenTrie, repair_choices, constrained_generate


class CandidateTests(unittest.TestCase):
    def test_bounded_edit_inverses(self):
        self.assertIn('gokai', set(edits('ogkai')))
        self.assertIn('kazoku', set(edits('kzoku')))
        self.assertIn('kazoku', set(edits('kqzoku')))
        values = list(edits('a' * 64))
        self.assertLess(len(values), 1024)
        self.assertEqual(len(values), len(set(values)))
        with self.assertRaises(ValueError):
            list(edits('A' * 65))

    def test_trie_cannot_generate_unlisted_reading(self):
        trie = TokenTrie([[4, 3], [6, 10, 3], [6, 11, 12, 3]])
        self.assertEqual(trie.allowed([]), [4, 6])
        self.assertEqual(trie.allowed([6, 11]), [12])
        self.assertEqual(trie.allowed([6, 10]), [3])
        with self.assertRaises(ValueError):
            trie.allowed([6, 12])

    def test_dictionary_match_is_only_a_reading_check(self):
        verifier = ReadingVerifier.__new__(ReadingVerifier)
        verifier.options = {'会議': {'かいぎ'}, '資料': {'しりょう'}, 'を': {'を'}}
        self.assertTrue(verifier.matches('会議の資料', 'かいぎのしりょう'))
        self.assertFalse(verifier.matches('会議の資料', 'かいにのしりょう'))
        self.assertFalse(verifier.matches('不明語', 'ふめいご'))

    def test_choice_generation_does_not_consult_gold(self):
        class Codec:
            ids = {'<keep>': 4, '<abstain>': 5, '<emit>': 6}
            def text(self, text):
                return [ord(c) for c in text]
        case = {'observed': 'おgかい', 'candidates': [{'reading': 'ごかい'}, {'reading': 'おgかい'}],
                'reference_reading': 'ごかい'}
        choices = repair_choices(case, Codec())
        self.assertEqual(choices, repair_choices({**case, 'reference_reading': 'うそ'}, Codec()))
        self.assertEqual(len(choices), 3)

    def test_batched_completion_padding_stays_inside_choices(self):
        from transformers import GPT2Config, GPT2LMHeadModel
        model = GPT2LMHeadModel(GPT2Config(vocab_size=20, n_embd=8, n_head=2, n_layer=1,
                                          n_positions=32, bos_token_id=2, eos_token_id=3, pad_token_id=1)).eval()
        outputs = constrained_generate(model, [[8], [8, 9]], [[[4, 3]], [[5, 6, 7, 3]]], 'cpu', 8)
        self.assertEqual(outputs, [[4, 3], [5, 6, 7, 3]])


if __name__ == '__main__':
    unittest.main()
