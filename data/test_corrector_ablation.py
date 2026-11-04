import json
from pathlib import Path
import struct
import tempfile
import unittest

from build_corrector_ablation import dictionary_seeds, seed_rows
from dataset_isolation import evaluation_keys, load_jsonl, validate_rows


class AblationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Path('artifacts').mkdir(exist_ok=True)

    def test_dictionary_filter_does_not_use_reserved_readings_or_answers(self):
        entries = [('検査', 'ケンサ', -1.), ('検査薬', 'ケンサヤク', -2.), ('調査', 'チョウサ', -3.)]
        records = []
        for surface, reading, score in entries:
            a, b = surface.encode(), reading.encode()
            records.append(struct.pack('<HHf', len(a), len(b), score) + a + b)
        offsets = [8 + 4*(len(records)+1)]
        for record in records:
            offsets.append(offsets[-1] + len(record))
        with tempfile.TemporaryDirectory(dir='artifacts') as tmp:
            path = Path(tmp)/'dictionary.bin'
            path.write_bytes(b'AZR2'+struct.pack('<I',len(records))+struct.pack('<4I',*offsets)+b''.join(records))
            seeds = dictionary_seeds(path, {'けんさ', '調査'}, 10)
        self.assertEqual([r['completion'] for r in seeds], ['検査薬'])

    def test_mixed_canonical_reading_is_excluded_rather_than_guessed(self):
        rows = [{'prompt':'\uee00オ伺イシマス\uee01','target_reading':'お伺いします','completion':'お伺いします'},
                {'prompt':'\uee00チョウサ\uee01','target_reading':'ちょうさ','completion':'調査'}]
        with tempfile.TemporaryDirectory(dir='artifacts') as tmp:
            path = Path(tmp)/'rows.jsonl'
            path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
            self.assertEqual([r['completion'] for r in seed_rows([path])], ['調査'])

    def test_real_experiment_pairs_and_shared_holdout_are_isolated(self):
        root = Path('artifacts/datasets/corrector-ablation-20261027')
        if not root.exists():
            self.skipTest('Generate the optional experiment corpus first')
        reserved = evaluation_keys()
        inputs, counts = [], []
        for pool in ('narrow', 'diverse'):
            surface, reading = [load_jsonl(root/f'{pool}-{task}/train.jsonl') for task in ('surface','reading')]
            self.assertEqual([(r['prompt'],r['source_group']) for r in surface], [(r['prompt'],r['source_group']) for r in reading])
            self.assertTrue(all(r['completion']==r['target_reading'] for r in reading))
            counts.append(len(surface))
            for task, train in [('surface',surface),('reading',reading)]:
                val = load_jsonl(root/f'{pool}-{task}/val.jsonl')
                self.assertFalse(any(validate_rows(train,val,reserved).values()))
                if task=='surface':
                    inputs.append([(r['prompt'],r['completion']) for r in val])
        self.assertEqual(counts[0],counts[1])
        self.assertEqual(inputs[0],inputs[1])


if __name__ == '__main__':
    unittest.main()
