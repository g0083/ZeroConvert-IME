# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import tempfile
import unittest

from prepare_origin_token_cache import tokenizer_sample


class TokenizerSampleTests(unittest.TestCase):
    def test_sample_is_bounded_reproducible_and_contains_only_source_rows(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / 'artifacts/results') as folder:
            path = Path(folder) / 'train.jsonl'
            lines = [json.dumps({'id': i}) + '\n' for i in range(1000)]
            path.write_text(''.join(lines), encoding='utf-8')
            sample, provenance = tokenizer_sample(path, 17, 42)
            self.assertEqual(len(sample), 17)
            self.assertEqual(len(set(sample)), 17)
            self.assertTrue(set(sample) <= set(lines))
            self.assertEqual(provenance['population_rows'], 1000)
            self.assertEqual((sample, provenance), tokenizer_sample(path, 17, 42))
            self.assertNotEqual(sample, tokenizer_sample(path, 17, 43)[0])
            full, _ = tokenizer_sample(path, 2000, 42)
            self.assertEqual(full, lines)


if __name__ == '__main__':
    unittest.main()
