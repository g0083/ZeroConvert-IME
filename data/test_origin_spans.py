# SPDX-License-Identifier: Apache-2.0
import copy
from pathlib import Path
import tempfile
import unittest

from augment_origin_spans import spans
from verify_origin_spans import identical_prefix, validate_span


class SpanTests(unittest.TestCase):
    def setUp(self):
        self.tokens = [('棚', 'たな'), ('の', 'の'), ('資料', 'しりょう'), ('を', 'を'),
                       ('確認', 'かくにん'), ('し', 'し'), ('ます', 'ます'), ('。', '。')]
        self.sentence = ''.join(s for s, _ in self.tokens)
        self.row = {'id': 'doc-1-s1', 'task': 'convert', 'category': 'full', 'document_id': 'doc-1',
                    'source_text': self.sentence, 'source_id': 'source',
                    'input': {'ctx': '受付の仕事です。', 'reading': ''.join(r for _, r in self.tokens)},
                    'target': {'action': 'emit', 'text': self.sentence}}

    def test_spans_use_only_preceding_context_and_exact_source_alignment(self):
        original = copy.deepcopy(self.row)
        extras = spans(self.row, self.tokens, 42)
        self.assertEqual(len(extras), 2)
        self.assertEqual(extras, spans(self.row, self.tokens, 42))
        for row in extras:
            validate_span(self.row, row, self.tokens)
            span = row['source_span']
            self.assertEqual(row['target']['text'], self.sentence[span['start']:span['end']])
            self.assertEqual(row['input']['reading'], ''.join(r for _, r in self.tokens[span['token_start']:span['token_end']]))
            self.assertEqual(row['input']['ctx'], (self.row['input']['ctx'] + self.sentence[:span['start']])[-48:])
            self.assertEqual(row['document_id'], self.row['document_id'])
            self.assertEqual(row['source_text'], self.row['source_text'])
            self.assertNotEqual(row['target']['text'], self.sentence)
        self.assertEqual(self.row, original)

    def test_nonfull_nonconversion_and_misaligned_parent(self):
        self.assertEqual(spans({**self.row, 'category': 'prefix'}, self.tokens, 42), [])
        self.assertEqual(spans({'task': 'predict'}, [], 42), [])
        with self.assertRaises(ValueError):
            spans({**self.row, 'input': {'reading': 'べつのよみ'}}, self.tokens, 42)
        with self.assertRaises(ValueError):
            spans(self.row, self.tokens[:-1], 42)
        with self.assertRaises(ValueError):
            spans({**self.row, 'input': {**self.row['input'], 'protected': []}}, self.tokens, 42)

    def test_no_invented_word_or_full_sentence_when_no_span_available(self):
        tokens = [('棚', 'たな')]
        row = {**self.row, 'source_text': '棚', 'input': {'reading': 'たな'}, 'target': {'action': 'emit', 'text': '棚'}}
        self.assertEqual(spans(row, tokens, 42), [])

    def test_verifier_rejects_future_context_changed_reading_and_source(self):
        row = spans(self.row, self.tokens, 42)[0]
        mutations = [
            {**row, 'document_id': 'validation-document'},
            {**row, 'input': {**row['input'], 'ctx': self.sentence}},
            {**row, 'input': {**row['input'], 'reading': 'べつのよみ'}},
            {**row, 'source_span': {**row['source_span'], 'end': row['source_span']['end'] + 1}},
        ]
        for mutation in mutations:
            with self.assertRaises(ValueError):
                validate_span(self.row, mutation, self.tokens)

    def test_original_training_bytes_cannot_be_changed_or_removed(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / 'artifacts/results') as directory:
            parent, child = Path(directory) / 'parent', Path(directory) / 'child'
            parent.write_bytes(b'original\nsecond\n')
            child.write_bytes(parent.read_bytes() + b'new-span\n')
            self.assertEqual(identical_prefix(parent, child), parent.stat().st_size)
            for corrupted in (b'changed!\nsecond\nnew-span\n', b'original\n'):
                child.write_bytes(corrupted)
                with self.assertRaises(ValueError):
                    identical_prefix(parent, child)


if __name__ == '__main__':
    unittest.main()
