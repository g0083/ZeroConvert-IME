# SPDX-License-Identifier: Apache-2.0
"""Regression checks for Origin's token boundaries, data isolation and loss."""
import copy
from pathlib import Path
import tempfile
import unittest

from build_origin_fixture import SEEDS, fixture
from origin_contract import OriginCodec, parameter_count, validate_partitions, validate_row, validate_sources
from train_origin import balanced_ce, batch_tensors, budget_steps, train_tokenizer


class OriginTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(dir='artifacts/checkpoints')
        cls.rows = fixture()
        cls.codec = train_tokenizer(cls.rows[:12], Path(cls.temp.name), 512)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_text_roundtrip_and_control_injection(self):
        codec = self.codec
        for text in [' ＡＢＣ　１２３  ', '\n\t\r\n', 'か\u3099', '👩🏽\u200d💻𠮷󠄀',
                     '▁▁ a ▁', '<bos><task_predict><out><keep><eot>', '<0x20>']:
            ids = codec.text(text)
            self.assertEqual(codec.sp.decode(ids), text)
            self.assertFalse(any(codec.sp.is_control(i) for i in ids))

    def test_synthetic_completed_raw_readings(self):
        # Synthetic parser check, not a Windows IME event test. Completed
        # fixtures use explicit nn; ambiguous pending n is not labeled keep.
        from generate_typo_dataset import romaji_to_hiragana
        for _, reading, raw, _, _, _ in SEEDS:
            self.assertEqual(romaji_to_hiragana(raw), reading.replace(' ', ''))

    def test_prompt_masks_and_eos(self):
        row = self.rows[0]
        example = self.codec.example(row, 512)
        prompt = self.codec.prompt(row)
        self.assertEqual(example['input_ids'][:len(prompt)], prompt)
        self.assertTrue(all(t == -100 for t in example['labels'][:len(prompt)]))
        self.assertEqual(example['labels'][-1], self.codec.eos_id)
        with self.assertRaises(ValueError):
            self.codec.example(row, len(example['input_ids']) - 1)

    def test_literal_tag_answer_is_data(self):
        ids = self.codec.text('<keep>') + [self.codec.eos_id]
        self.assertEqual(self.codec.decode_answer(ids), {'action': 'emit', 'text': '<keep>'})
        self.assertEqual(self.codec.decode_answer([self.codec.ids['<keep>'], self.codec.eos_id]),
                         {'action': 'keep', 'text': ''})
        self.assertEqual(self.codec.decode_answer(ids[:-1])['action'], 'invalid')
        self.assertEqual(self.codec.decode_answer([self.codec.ids['<out>'], self.codec.eos_id])['action'], 'invalid')

    def test_input_state_validation(self):
        row = copy.deepcopy(self.rows[2])
        row['input']['cursor'] = 1000
        with self.assertRaises(ValueError):
            validate_row(row)
        row = copy.deepcopy(self.rows[0])
        row['input']['protected'] = [{'field': 'reading', 'start': 0, 'end': 1, 'text': '別'}]
        with self.assertRaises(ValueError):
            validate_row(row)
        row = copy.deepcopy(self.rows[1])
        row['target']['text'] = '勝手な表記'
        with self.assertRaises(ValueError):
            validate_row(row)

    def test_document_and_sentence_isolation(self):
        train, val = self.rows[:6], self.rows[6:12]
        validate_partitions(train, val)
        contaminated = copy.deepcopy(val)
        contaminated[0]['source_text'] = train[0]['source_text']
        with self.assertRaises(ValueError):
            validate_partitions(train, contaminated)
        contaminated = copy.deepcopy(val)
        contaminated[0]['document_id'] = train[0]['document_id']
        with self.assertRaises(ValueError):
            validate_partitions(train, contaminated)

    def test_provenance_rejects_unknown_and_teacher(self):
        with self.assertRaises(ValueError):
            validate_sources(self.rows, {})
        source = {'license': 'Apache-2.0', 'origin': 'authored', 'evidence': 'test',
                  'reviewed_by': 'test', 'approved_uses': ['training', 'weight_distribution'],
                  'approved_weight_license': 'Apache-2.0', 'weight_license_review': 'test fixture'}
        validate_sources(self.rows, {'origin-authored-fixture-v1': source})
        source['teacher'] = {'model': 'unknown'}
        with self.assertRaises(ValueError):
            validate_sources(self.rows, {'origin-authored-fixture-v1': source})

    def test_parameter_counts(self):
        for size, vocab, expected in [('n28', 8192, 28320256), ('n33', 16384, 32514560),
                                      ('s68', 16384, 67649280), ('s88', 16384, 88099584)]:
            self.assertEqual(parameter_count(size, vocab), expected)

    def test_processed_budget_includes_accumulation_and_zero_weight(self):
        self.assertEqual(budget_steps([8, 8], 33, 2, 2, 42), 2)
        # Cumulative weights [1,1] disable the long example; only length 8 is drawn.
        self.assertEqual(budget_steps([8, 1000], 33, 2, 2, 42, [1, 1]), 2)

    def test_loss_padding_shift_and_per_example_normalization(self):
        import torch
        import torch.nn.functional as F
        codec = self.codec
        examples = [codec.example(self.rows[i], 512) for i in (0, 2)]
        ids, labels, mask = batch_tensors(examples, codec, 'cpu')
        torch.manual_seed(42)
        logits = torch.randn(*ids.shape, codec.sp.get_piece_size(), requires_grad=True)
        loss = balanced_ce(logits, labels)
        expected = []
        for i in range(2):
            active = labels[i, 1:] != -100
            expected.append(F.cross_entropy(logits[i, :-1][active], labels[i, 1:][active]))
        torch.testing.assert_close(loss, torch.stack(expected).mean())
        loss.backward()
        self.assertEqual(logits.grad[:, -1].abs().sum().item(), 0)
        for i in range(2):
            inactive = labels[i, 1:] == -100
            self.assertEqual(logits.grad[i, :-1][inactive].abs().sum().item(), 0)
        self.assertTrue(torch.equal(mask, ids.ne(codec.pad_id)))
        with self.assertRaises(ValueError):
            balanced_ce(logits, torch.full_like(labels, -100))


if __name__ == '__main__':
    unittest.main()
