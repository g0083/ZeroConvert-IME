# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from curate_origin_repair import curate
from keyboard_hybrid import RomanDecoder


class CurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.decoder = RomanDecoder()

    def row(self, raw, reference, pending=''):
        return {'task': 'repair_qwerty', 'input': {'ctx': '', 'raw': raw, 'pending': pending},
                'reference_reading': reference, 'target': {'action': 'emit', 'text': reference}}

    def test_valid_alias_is_keep_without_changing_input_or_original(self):
        row = self.row('siry ou'.replace(' ', ''), 'しりょう')
        original = copy.deepcopy(row)
        result, reason = curate(row, self.decoder)
        self.assertEqual(reason, 'equivalent_to_keep')
        self.assertEqual(result['target'], {'action': 'keep', 'text': ''})
        self.assertEqual(result['input'], original['input'])
        self.assertEqual(row, original)

    def test_real_substitution_still_needs_repair(self):
        row = self.row('siryou', 'しりょく')
        result, reason = curate(row, self.decoder)
        self.assertIs(result, row)
        self.assertIsNone(reason)

    def test_terminal_ambiguity_quarantined_but_observed_pending_preserved(self):
        for raw in ('shin', 'shirit'):
            result, reason = curate(self.row(raw, 'しる'), self.decoder)
            self.assertIsNone(result)
            self.assertIn('review', reason)
        row = self.row('shirit', 'しる', pending='t')
        self.assertIs(curate(row, self.decoder)[0], row)

    def test_nonrepair_is_unchanged(self):
        row = {'task': 'convert'}
        self.assertEqual(curate(row, self.decoder), (row, None))


if __name__ == '__main__':
    unittest.main()
