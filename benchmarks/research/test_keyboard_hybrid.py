"""Behavior checks for the offline experiment and its false-suggestion metric."""
import unittest
import hashlib
import json
import tempfile
from pathlib import Path

from analyze_keyboard_hybrid import metrics, ranking, prevalence_sensitivity, provenance
from keyboard_hybrid import NEIGHBORS, RomanDecoder, generate, key_candidates


class KeyboardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.decoder = RomanDecoder()

    def test_romaji_aliases_nasal_and_geminate(self):
        for keys, expected in [('shinsei', 'しんせい'), ('sinsei', 'しんせい'),
                               ('sannka', 'さんか'), ('konnnitiha', 'こんにちは'),
                               ('gakkou', 'がっこう'), ('shinyou', 'しにょう'),
                               ('shinnyou', 'しんよう'), ('hon', 'ほん')]:
            self.assertEqual(self.decoder.decode(keys), expected)

    def test_a_s_both_directions_can_recover_keys(self):
        original = 'ashitahaasadesu'
        for i, old in enumerate(original):
            if old in 'as':
                noisy = original[:i]+('s' if old == 'a' else 'a')+original[i+1:]
                self.assertIn(original, {k for k, _ in key_candidates(noisy)})

    def test_adjacency_is_symmetric_and_bounded(self):
        for key, neighbors in NEIGHBORS.items():
            self.assertNotIn(key, neighbors)
            for other in neighbors:
                self.assertIn(key, NEIGHBORS[other])
        with self.assertRaises(ValueError):
            list(key_candidates('a'*65))

    def test_original_survives_dictionary_pruning(self):
        class FakeLexicon:
            def convert(self, reading, beam):
                return ((-100 if reading == 'あさ' else 0, reading, 0),)
        candidates, _ = generate('asa', self.decoder, FakeLexicon(), reading_limit=1)
        self.assertEqual([c['reading'] for c in candidates], ['あさ'])

    def test_candidate_generation_has_no_gold_argument(self):
        # An unknown word cannot cause a gold-dependent rescue path.
        class FakeLexicon:
            def convert(self, reading, beam):
                return ((-len(reading), reading, 0),)
        first = generate('aaadesu', self.decoder, FakeLexicon())
        second = generate('aaadesu', self.decoder, FakeLexicon())
        self.assertEqual(first, second)


def fixture(kind='a_s', expected='あした'):
    return {'kind': kind, 'edit': [1, 'a', 's'], 'observed_reading': 'ああた',
            'expected_reading': expected, 'candidates': [
                {'reading': 'ああた', 'surface': 'ああた', 'dictionary': -20, 'lm_sum': -3},
                {'reading': 'あした', 'surface': '明日', 'dictionary': -10, 'lm_sum': -4},
                {'reading': 'あした', 'surface': 'あした', 'dictionary': -11, 'lm_sum': -5},
                {'reading': 'あった', 'surface': 'あった', 'dictionary': -11, 'lm_sum': -4}]}


class AnalysisTests(unittest.TestCase):
    def test_provenance_binds_mode_tokenizer_and_result(self):
        scratch = Path(__file__).resolve().parents[2]/'artifacts/results/keyboard-hybrid-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            path = Path(directory)/'scores.jsonl'
            path.write_text('{}\n', encoding='utf-8')
            meta = {'result_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                    'generation': {'dictionary': 'first'}, 'model_files': {'merges.txt': 'hash'},
                    'mode': 'reading-prior', 'device': 'cuda'}
            sidecar = path.with_suffix('.metadata.json')
            sidecar.write_text(json.dumps(meta), encoding='utf-8')
            original = provenance(path, True)
            meta['mode'] = 'conditional'
            sidecar.write_text(json.dumps(meta), encoding='utf-8')
            self.assertNotEqual(original, provenance(path, True))
            meta['mode'] = 'reading-prior'
            meta['model_files']['merges.txt'] = 'different'
            sidecar.write_text(json.dumps(meta), encoding='utf-8')
            self.assertNotEqual(original, provenance(path, True))
            path.write_text('{"changed": true}\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                provenance(path, True)

    def test_missing_and_nonfinite_scores_are_not_dictionary_fallback(self):
        for bad in (None, float('nan'), float('inf')):
            row = fixture()
            if bad is None:
                del row['candidates'][0]['lm_sum']
            else:
                row['candidates'][0]['lm_sum'] = bad
            with self.assertRaises(ValueError):
                ranking(row, {'dictionary_weight': 1, 'lm_weight': 1, 'margin': 5})

    def test_original_is_not_reported_as_correction(self):
        policy = {'dictionary_weight': 1, 'lm_weight': 0, 'margin': 11}
        self.assertEqual(ranking(fixture(), policy), [])

    def test_unique_readings_and_normal_false_suggestions(self):
        policy = {'dictionary_weight': 1, 'lm_weight': 0, 'margin': 5}
        rows = [fixture(), fixture('other_adjacent'), fixture('clean', 'ああた')]
        result = metrics(rows, policy)
        self.assertEqual(len(ranking(rows[0], policy)), 2)
        self.assertEqual(result['a_s']['top1_correct'], 1)
        self.assertEqual(result['a_to_s']['n'], 1)
        self.assertEqual(result['s_to_a']['n'], 0)
        self.assertEqual(result['clean']['wrong_top1'], 1)
        self.assertEqual(result['clean']['top3_correct'], 0)
        self.assertAlmostEqual(prevalence_sensitivity(result)[0]['top1_precision'], .01)

    def test_top3_success_does_not_hide_wrong_top1(self):
        result = metrics([fixture(expected='あった')],
                         {'dictionary_weight': 1, 'lm_weight': 0, 'margin': 5})
        self.assertEqual(result['a_s']['top1_correct'], 0)
        self.assertEqual(result['a_s']['top3_correct'], 1)
        self.assertEqual(result['a_s']['wrong_top1'], 1)


if __name__ == '__main__':
    unittest.main()
