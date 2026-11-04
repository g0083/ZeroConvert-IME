# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from build_origin_fixture import fixture
from origin_contract import TASKS, digest, write_json
from origin_token_cache import INDEX, TokenCache, write_partition
from train_origin import train_tokenizer


class TokenCacheTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(dir='artifacts/checkpoints')
        cls.path = Path(cls.temp.name)
        cls.rows = fixture()[:12]
        cls.codec = train_tokenizer(cls.rows, cls.path, 512)
        meta = write_partition(cls.rows, cls.codec, cls.path, 'train', 256, list(TASKS))
        write_json(cls.path / 'cache-manifest.json', {'schema': 'origin-token-cache-v1',
            'partitions': {'train': meta}, 'task_names': list(TASKS), 'eos_id': cls.codec.eos_id})

    @classmethod
    def tearDownClass(cls):
        assert cls.path.resolve().is_relative_to(Path('artifacts/checkpoints').resolve())
        cls.temp.cleanup()

    def test_random_access_recreates_every_label_and_eos(self):
        with TokenCache(self.path, 'train') as cache:
            for index in (11, 0, 7, 2, 9):
                self.assertEqual(cache[index], self.codec.example(self.rows[index], 256))
            lengths, tasks = cache.lengths_and_tasks()
            self.assertEqual(list(lengths), [len(self.codec.example(r, 256)['input_ids']) for r in self.rows])
            self.assertEqual(list(tasks), [list(TASKS).index(r['task']) for r in self.rows])
            with self.assertRaises(IndexError):
                cache[len(self.rows)]

    def test_file_corruption_is_detected_before_use(self):
        with tempfile.TemporaryDirectory(dir='artifacts/checkpoints') as temporary:
            target = Path(temporary) / 'copy'
            shutil.copytree(self.path, target)
            with (target / 'train.tokens').open('ab') as output:
                output.write(b'bad')
            with self.assertRaises(ValueError):
                TokenCache(target, 'train')

    def test_declared_valid_hash_cannot_hide_invalid_offsets(self):
        with tempfile.TemporaryDirectory(dir='artifacts/checkpoints') as temporary:
            target = Path(temporary) / 'copy'
            shutil.copytree(self.path, target)
            index = target / 'train.index'
            value = bytearray(index.read_bytes())
            _, length, prompt, task = INDEX.unpack_from(value)
            INDEX.pack_into(value, 0, (target / 'train.tokens').stat().st_size + 2, length, prompt, task)
            index.write_bytes(value)
            manifest = json.loads((target / 'cache-manifest.json').read_text(encoding='utf-8'))
            manifest['partitions']['train']['index_sha256'] = digest(index)
            write_json(target / 'cache-manifest.json', manifest)
            with TokenCache(target, 'train') as cache:
                with self.assertRaises(ValueError):
                    cache[0]


if __name__ == '__main__':
    unittest.main()
