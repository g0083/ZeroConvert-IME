# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from origin_sampling import sampling_weights, verify_augmentation_binding
from train_origin_cached import cached_weights


class SamplingTests(unittest.TestCase):
    def test_no_action_override_is_bitwise_the_original_cdf(self):
        tasks = [0, 1, 0, 1, 2, 1, 2, 0]
        names = ['convert', 'repair_qwerty', 'predict']
        weights = {'convert': .45, 'repair_qwerty': .30, 'predict': .20}
        actual, _ = sampling_weights(tasks, names, weights, [0, 1, 0, 0, 2, 2, 0, 0])
        self.assertEqual(list(actual), list(cached_weights(tasks, names, weights)))

    def test_keep_emit_abstain_weights_have_explicit_separate_mass(self):
        tasks, actions = [0, 1, 1, 1, 1], [0, 1, 1, 0, 2]
        cdf, populations = sampling_weights(tasks, ['convert', 'repair_qwerty'], {'convert': .5, 'repair_qwerty': .5}, actions,
                        {'repair_qwerty': {'keep': 65, 'emit': 25, 'abstain': 10}})
        increments = [cdf[0]] + [b - a for a, b in zip(cdf, cdf[1:])]
        self.assertAlmostEqual(sum(increments[i] for i in (1, 2)), .5 * .65)
        self.assertAlmostEqual(increments[3], .5 * .25)
        self.assertAlmostEqual(increments[4], .5 * .10)
        self.assertEqual(populations['repair_qwerty'], {'keep': 2, 'emit': 1, 'abstain': 1})
        with self.assertRaises(ValueError):
            sampling_weights([0], ['convert'], {'convert': 1}, [0], {'convert': {'keep': 1}})

    def test_augmentation_cannot_change_validation_or_switch_parent(self):
        parent = {'train.jsonl': 'old', 'val.jsonl': 'val', 'sources.json': 'sources'}
        derived = {**parent, 'train.jsonl': 'extended'}
        base = {'tokenizer_sha256': 'vocab', 'dataset_hashes': parent, 'token_cache_manifest_sha256': 'parent-cache'}
        meta = {'tokenizer_sha256': 'vocab', 'dataset_hashes': derived, 'dataset_manifest_sha256': 'new-manifest',
                'tokenizer_transfer': {'parent_cache_manifest_sha256': 'parent-cache'}}
        lineage = {'status': 'PASS', 'dataset_manifest_sha256': 'new-manifest', 'parent_manifest_sha256': 'old-manifest',
                   'parent_output_hashes': parent, 'output_hashes': derived, 'verified_spans': 10}
        isolation = {'status': 'PASS', 'manifest_sha256': 'new-manifest'}
        original = {'dataset_hashes': parent, 'dataset_manifest_sha256': 'old-manifest'}
        manifest = {'operation': 'source-aligned training conversion span augmentation'}
        result = verify_augmentation_binding(base, meta, lineage, isolation, manifest, original)
        self.assertEqual(result['kind'], 'verified-training-span-augmentation')
        corrupted = copy.deepcopy(meta)
        corrupted['dataset_hashes']['val.jsonl'] = 'changed'
        with self.assertRaises(ValueError):
            verify_augmentation_binding(base, corrupted, lineage, isolation, manifest, original)
        consistent_but_wrong = copy.deepcopy(lineage)
        consistent_but_wrong['output_hashes'] = corrupted['dataset_hashes']
        with self.assertRaises(ValueError):
            verify_augmentation_binding(base, corrupted, consistent_but_wrong, isolation, manifest, original)
        with self.assertRaises(ValueError):
            verify_augmentation_binding({**base, 'token_cache_manifest_sha256': 'different-cache'}, meta, lineage, isolation, manifest, original)
        with self.assertRaises(ValueError):
            verify_augmentation_binding(base, meta, None, isolation, manifest, original)

    def test_context_balance_preserves_action_mass_and_removes_presence_prior(self):
        tasks = [0] * 6
        actions = [1, 1, 1, 0, 0, 2]
        states = [0, 0, 1, 0, 1, 0]
        cdf, _ = sampling_weights(tasks, ['repair_qwerty'], {'repair_qwerty': 1}, actions,
                       {'repair_qwerty': {'keep': 65, 'emit': 25, 'abstain': 10}}, states, True)
        increments = [cdf[0]] + [b - a for a, b in zip(cdf, cdf[1:])]
        self.assertAlmostEqual(increments[0] + increments[1], .325)
        self.assertAlmostEqual(increments[2], .325)
        self.assertAlmostEqual(increments[3], .125)
        self.assertAlmostEqual(increments[4], .125)
        self.assertAlmostEqual(increments[5], .10)
        with self.assertRaises(ValueError):
            sampling_weights([0, 0], ['repair_qwerty'], {'repair_qwerty': 1}, [1, 0],
                             context_states=[0, 0], balance_repair_context=True)


if __name__ == '__main__':
    unittest.main()
