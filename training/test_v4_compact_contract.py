# SPDX-License-Identifier: Apache-2.0
import copy
from pathlib import Path
import tempfile
import unittest

from transformers import AutoTokenizer
from v4_unified_contract import V4UnifiedCodec, build_tokenizer
from build_v4_repair_comparison import paired_rows
from build_v4_unified_dataset import isolation_keys

PARENT = Path('artifacts/checkpoints/standard-v4-nano-transfer-merged')


class CompactContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.reading = build_tokenizer(PARENT, cls.root / 'reading', True, compact=True, repair_input='observed')
        cls.surface = build_tokenizer(PARENT, cls.root / 'surface', True, compact=True,
                                      repair_input='observed', repair_output='surface')
        cls.raw = build_tokenizer(PARENT, cls.root / 'raw', True, compact=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_verbatim_path_and_literal_tags(self):
        for text in ('C:\\Users\\Tomoki\\Models', '"null"\t <out><missing> </s> 😀'):
            row = {'task': 'predict', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                   'input': {'ctx': text, 'surface': '', 'pending': ''}}
            prompt = self.reading.prompt(row)
            start = prompt.index(self.reading.ids['<ctx>']) + 1
            end = prompt.index(self.reading.ids['<surface>'])
            self.assertEqual(self.reading.data_tokenizer.decode(prompt[start:end]), text)

    def test_missing_empty_and_literal_null_differ(self):
        row = {'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'reading': 'てすと'}}
        missing = self.reading.prompt(row)
        explicit = self.reading.prompt({**row, 'input': {**row['input'], 'protected': 'null'}})
        empty = self.reading.prompt({**row, 'input': {**row['input'], 'protected': ''}})
        self.assertEqual(len({tuple(missing), tuple(explicit), tuple(empty)}), 3)

    def test_conversion_path_and_observation_identity(self):
        path = 'C:\\Dev\\Cache'
        row = {'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'reading': path}, 'target': {'action': 'emit', 'text': path}}
        prompt = self.reading.prompt(row)
        a, b = prompt.index(self.reading.ids['<reading>'])+1, prompt.index(self.reading.ids['<protected>'])
        self.assertEqual(self.reading.data_tokenizer.decode(prompt[a:b]), path)
        self.assertEqual(self.reading.decode_answer(self.reading.answer(row), 'convert'), row['target'])
        source = {'task': 'repair', 'document_id': 'a', 'source_text': '語句',
                  'input': {'ctx': '文脈', 'reading': 'カゾク', 'pending': ''}}
        other = {**source, 'input': {**source['input'], 'reading': 'かぞく'}}
        self.assertEqual(isolation_keys(source), isolation_keys(other))

    def test_paired_prompts_do_not_use_targets(self):
        row = {'task': 'repair', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'reading': 'おgかい', 'pending': ''},
               'target': {'action': 'emit', 'text': 'ごかい'}}
        self.assertEqual(self.reading.prompt(row), self.surface.prompt(row))
        changed = {**row, 'target': {'action': 'emit', 'text': '誤解'}}
        self.assertEqual(self.reading.prompt(row), self.reading.prompt(changed))
        self.surface.answer(changed)
        with self.assertRaises(ValueError):
            self.reading.answer(changed)
        with self.assertRaises(ValueError):
            self.raw.prompt(row)
        pending = {**row, 'input': {**row['input'], 'pending': 'k'}}
        with self.assertRaises(ValueError):
            self.reading.answer(pending)

    def test_existing_ids_preserved_and_reload(self):
        old = AutoTokenizer.from_pretrained(PARENT, local_files_only=True)
        self.assertTrue(all(self.reading.tokenizer.get_vocab()[k] == v for k, v in old.get_vocab().items()))
        self.assertEqual(self.reading.ids['<emit>'], 6042)
        self.assertEqual(self.reading.ids['<missing>'], 6043)
        self.assertEqual(self.reading.ids, V4UnifiedCodec(self.root / 'reading').ids)

    def test_family_exclusion_and_original_observation(self):
        def row(group, observed, canonical, surface):
            return {'source_group': group, 'prompt': '\uee02文脈\uee00' + observed + '\uee01',
                    'completion': surface, 'target_reading': canonical}
        train = [row('ok', 'オgカイ', 'ごかい', '誤解'), row('blocked', 'アイ', 'あい', '愛'),
                 row('blocked', 'ウエ', 'うえ', '上')]
        val = [row('val', 'ハンコ', 'はんこ', '判子')]
        result, _ = paired_rows({'train': train, 'val': val}, {'あい'})
        self.assertEqual(len(result['train']), 2)
        self.assertEqual({r['source_group'] for r in result['train']}, {'ok'})
        self.assertEqual(result['train'][0]['input']['reading'], 'おgかい')
        self.assertNotIn('raw', result['train'][0]['input'])
        self.assertEqual(result['train'][0]['reference_surface'], '誤解')


if __name__ == '__main__':
    unittest.main()
