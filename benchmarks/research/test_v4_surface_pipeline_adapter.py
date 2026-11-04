# SPDX-License-Identifier: Apache-2.0
import unittest

from benchmark_v4_surface_pipeline import parse_prompt, legacy_reply, reconstructed_keep


class AdapterTests(unittest.TestCase):
    def test_observed_input_not_reverse_reconstructed(self):
        self.assertEqual(parse_prompt('\uee02文脈\uee00オgカイ\uee01'), ('文脈', 'おgかい'))
        self.assertEqual(parse_prompt('\uee00オgカイ\uee01'), ('', 'おgかい'))
        with self.assertRaises(ValueError):
            parse_prompt('\uee00オgカイ\uee01trailing')

    def test_keep_veto_is_not_reported_as_backend_failure(self):
        for action in ('keep', 'abstain'):
            result = legacy_reply({'action': action, 'text': ''}, 'おgかい', [6040, 3])
            self.assertEqual(result['content'], 'おgかい')
            self.assertTrue(result['stopped_eos'])
            self.assertFalse(result['stopped_limit'])
        failed = legacy_reply({'action': 'invalid', 'text': ''}, 'おgかい', [1])
        self.assertTrue(failed['stopped_limit'])

    def test_upstream_reconstruction_still_needs_surface_conversion(self):
        keep = {'action': 'keep', 'text': ''}
        self.assertTrue(reconstructed_keep(keep, 'つくえをいどうした', 'つくえをいxおうした'))
        self.assertFalse(reconstructed_keep(keep, 'つくえをいどうした', 'つくえをいどうした'))
        self.assertFalse(reconstructed_keep(keep, 'つくえをいどうした', None))
        self.assertFalse(reconstructed_keep({'action': 'emit', 'text': '机を移動した'}, 'つくえをいどうした', 'つくえをいxおうした'))


if __name__ == '__main__':
    unittest.main()
