# SPDX-License-Identifier: Apache-2.0
import copy
import math
import unittest

from analyze_origin_operating_points import binomial_lower, binomial_upper, operating_point, proposal_decision


class Decoder:
    def decode(self, raw):
        return {'kaigi': 'かいぎ', 'kaiji': 'かいじ'}[raw]


def case(identifier, raw, expected, actual, reference='かいぎ', score=.99, pending=''):
    return {'id': identifier, 'document_id': identifier, 'input': {'raw': raw, 'pending': pending},
            'expected': expected, 'actual': actual, 'reference_reading': reference,
            'generation_score_features': {'emission_score': score}}


class OperatingPointTests(unittest.TestCase):
    def test_exact_binomial_boundaries_and_small_known_case(self):
        self.assertAlmostEqual(binomial_upper(0, 1000), 1 - .05 ** .001)
        self.assertAlmostEqual(binomial_upper(1, 2), math.sqrt(.95))
        self.assertAlmostEqual(binomial_lower(1, 2), 1 - math.sqrt(.95))
        self.assertEqual(binomial_lower(0, 2), 0)
        self.assertIsNone(binomial_upper(0, 0))
        self.assertGreater(binomial_upper(0, 1529), .001)
        self.assertLess(binomial_upper(0, 2995), .001)

    def test_noops_pending_and_nonreadings_cannot_inflate_success(self):
        keep = {'action': 'keep', 'text': ''}
        target = {'action': 'emit', 'text': 'かいぎ'}
        rows = [case('rescue', 'kaiji', target, target),
                case('noop', 'kaigi', keep, target),
                case('wrong', 'kaigi', keep, {'action': 'emit', 'text': 'かいじ'}),
                case('pending', 'kaiji', {'action': 'abstain', 'text': ''}, target, pending='k'),
                case('badtext', 'kaiji', target, {'action': 'emit', 'text': '会議'})]
        point = operating_point(rows, Decoder(), .9)
        self.assertEqual(point['counts']['accepted_reading_changes'], 2)
        self.assertEqual(point['reading_change_precision_point'], .5)
        self.assertEqual(point['noisy_reading_recall_point'], .5)
        self.assertEqual(point['normal_false_change_rate_point'], .5)
        self.assertEqual(point['filter_reasons']['unchanged_offline_observed_reading'], 1)
        self.assertEqual(point['filter_reasons']['observed_pending'], 1)
        self.assertFalse(point['automatic_adoption_approved'])

    def test_proposal_filter_has_no_access_to_answer_or_reference(self):
        row = case('test', 'kaiji', {'action': 'emit', 'text': 'かいぎ'}, {'action': 'emit', 'text': 'かいぎ'})
        changed = copy.deepcopy(row)
        changed['expected'] = {'action': 'abstain', 'text': ''}
        changed['reference_reading'] = 'べつのよみ'
        decisions = [proposal_decision(r['input'], r['actual'], r['generation_score_features'], Decoder(), .9) for r in (row, changed)]
        self.assertEqual(decisions, ['accepted', 'accepted'])

    def test_abstaining_everywhere_cannot_qualify_as_typo_recovery(self):
        abstain = {'action': 'abstain', 'text': ''}
        rows = [case('noisy', 'kaiji', {'action': 'emit', 'text': 'かいぎ'}, abstain),
                case('normal', 'kaigi', {'action': 'keep', 'text': ''}, abstain)]
        point = operating_point(rows, Decoder(), 0)
        self.assertEqual(point['counts']['accepted_reading_changes'], 0)
        self.assertEqual(point['noisy_reading_recall_point'], 0)
        self.assertIsNone(point['reading_change_precision_point'])
        self.assertFalse(point['automatic_adoption_approved'])


if __name__ == '__main__':
    unittest.main()
