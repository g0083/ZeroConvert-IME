# SPDX-License-Identifier: Apache-2.0
import math
import unittest

from origin_batch_inference import generated_score_features, generation_batches, unpad_generated


class BatchInferenceTests(unittest.TestCase):
    def test_each_example_keeps_its_serial_budget_without_context_overflow(self):
        prompts = [list(range(n)) for n in (5, 90, 180, 210, 5)]
        batches = generation_batches(prompts, 256, 2)
        indices = [i for _, group in batches for i in group]
        self.assertEqual(sorted(indices), list(range(len(prompts))))
        for allowance, group in batches:
            self.assertLessEqual(max(len(prompts[i]) for i in group) + allowance, 256)
            for i in group:
                self.assertEqual(allowance, min(96, 256 - len(prompts[i])))
        self.assertEqual([group[0] for _, group in generation_batches(prompts, 256, 1)], list(range(len(prompts))))
        with self.assertRaises(ValueError):
            generation_batches([[1] * 256], 256, 2)

    def test_padding_does_not_hide_invalid_body_or_invent_eos(self):
        self.assertEqual(unpad_generated([6, 2, 3, 3], 3, 2), [6, 2])
        self.assertEqual(unpad_generated([6, 3, 2, 3], 3, 2), [6, 3, 2])
        self.assertEqual(unpad_generated([6, 7, 3], 3, 2), [6, 7, 3])

    def test_generated_scores_exclude_control_mass_and_never_use_gold(self):
        import torch
        class Pieces:
            def get_piece_size(self): return 8
            def is_control(self, i): return i in (1, 2, 3, 4, 5)
            def unk_id(self): return 0
        class Codec:
            sp, pad_id, bos_id, eos_id = Pieces(), 3, 1, 2
            ids = {'<keep>': 4, '<abstain>': 5}
        first = torch.tensor([[.01, .01, .01, .01, .1, .06, .7, .1]] * 2).log()
        second = torch.tensor([[.1 / 7] * 2 + [.9] + [.1 / 7] * 5] * 2).log()
        features = generated_score_features([first, second], torch.tensor([[6, 2], [4, 2]]),
                    [[6, 2], [4, 2]], [{'action': 'emit'}, {'action': 'keep'}], Codec())
        self.assertAlmostEqual(features[0]['first_step_emit_mass'], .8, places=6)
        self.assertAlmostEqual(features[0]['emission_score'], .7, places=6)
        self.assertAlmostEqual(features[0]['mean_generated_log_probability'], (math.log(.7) + math.log(.9)) / 2, places=6)
        self.assertEqual(features[1]['emission_score'], 0)
        self.assertFalse(features[0]['calibrated_correctness_probability'])
        corrupted = first.clone()
        corrupted[0, 6] = float('nan')
        with self.assertRaises(ValueError):
            generated_score_features([corrupted, second], torch.tensor([[6, 2], [4, 2]]),
                    [[6, 2], [4, 2]], [{'action': 'emit'}, {'action': 'keep'}], Codec())


if __name__ == '__main__':
    unittest.main()
