# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import tempfile
import unittest

from append_origin_span_cache import append_sequences
from build_origin_fixture import fixture
from origin_contract import TASKS, write_json
from origin_token_cache import TokenCache, write_partition
from train_origin import train_tokenizer


class SpanCacheTests(unittest.TestCase):
    def test_append_preserves_original_bytes_and_loss_masks_across_boundary(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / 'artifacts/results') as folder:
            path = Path(folder)
            rows = fixture()
            old = rows[:12]
            added = [row for row in rows[12:] if row['task'] == 'convert'][:3]
            self.assertEqual(len(added), 3)
            codec = train_tokenizer(old + added, path, 512)
            meta = write_partition(old, codec, path, 'train', 256, list(TASKS))
            original_tokens, original_index = (path / 'train.tokens').read_bytes(), (path / 'train.index').read_bytes()
            updated = append_sequences(added, codec, path, meta, 256, list(TASKS))
            self.assertTrue((path / 'train.tokens').read_bytes().startswith(original_tokens))
            self.assertTrue((path / 'train.index').read_bytes().startswith(original_index))
            self.assertEqual(meta['rows'], len(old))  # parent metadata is immutable
            write_json(path / 'cache-manifest.json', {'schema': 'origin-token-cache-v1', 'partitions': {'train': updated},
                       'task_names': list(TASKS), 'eos_id': codec.eos_id})
            with TokenCache(path, 'train') as cache:
                self.assertEqual(len(cache), len(old) + len(added))
                for position, row in enumerate(old + added):
                    self.assertEqual(cache[position], codec.example(row, 256))
                self.assertEqual(updated['target_tokens_one_pass'], sum(sum(v != -100 for v in cache[i]['labels']) for i in range(len(cache))))


if __name__ == '__main__':
    unittest.main()
