# SPDX-License-Identifier: Apache-2.0
import unittest

from evaluate_origin_tasks import stratified_sample, summarize


def case(task, category, expected, actual, reference=None):
    return {'id': category, 'task': task, 'category': category, 'input': {'raw': 'kaigi'},
            'target': expected, 'expected': expected, 'actual': actual, 'exact': expected == actual,
            'reference_reading': reference}


class DiagnosticTests(unittest.TestCase):
    def test_noisy_recall_and_clean_changes_are_separate(self):
        emit = lambda value: {'action': 'emit', 'text': value}
        keep = {'action': 'keep', 'text': ''}
        abstain = {'action': 'abstain', 'text': ''}
        rows = [case('repair_qwerty', 'correct', emit('かいぎ'), emit('かいぎ')),
                case('repair_qwerty', 'wrong', emit('かいぎ'), emit('かいじ')),
                case('repair_qwerty', 'same-reading', keep, emit('かいぎ'), 'かいぎ'),
                case('repair_qwerty', 'changed', keep, emit('かいじ'), 'かいぎ'),
                case('repair_qwerty', 'pending', abstain, emit('かいぎ'), 'かいぎ'),
                case('repair_qwerty', 'kept', keep, keep, 'かいぎ')]
        result = summarize(rows)['repair_qwerty']
        self.assertEqual(result['noisy_exact_reading_recovery'], 1)
        self.assertEqual(result['clean_changed_reading_proposals'], 1)
        self.assertEqual(result['clean_unchanged_emit_proposals'], 1)
        self.assertEqual(result['pending_emit_proposals'], 1)
        self.assertEqual(result['correct_reading_proposals'], 2)
        self.assertEqual(result['proposal_precision'], 2 / 5)
        self.assertIsInstance(result['deployment_autocorrection_precision'], str)

    def test_abstaining_everywhere_cannot_pass_suffix_recall(self):
        result = summarize([case('predict', 'suffix', {'action': 'emit', 'text': 'します。'},
                                 {'action': 'abstain', 'text': ''}),
                            case('predict', 'complete', {'action': 'abstain', 'text': ''},
                                 {'action': 'abstain', 'text': ''})])['predict']
        self.assertEqual(result['strict_target_exact'], 1)
        self.assertEqual(result['suffix_exact'], 0)
        self.assertEqual(result['suffix_proposals'], 0)

    def test_numeric_width_regression_is_visible(self):
        result = summarize([case('convert', 'width', {'action': 'emit', 'text': 'Ａ１２円'},
                                 {'action': 'emit', 'text': 'A12円'})])['convert']
        self.assertEqual(result['protected_sequence_mismatches'], 1)

    def test_sample_is_deterministic_and_covers_strata(self):
        rows = [case('convert', category, {'action': 'emit', 'text': str(i)},
                     {'action': 'emit', 'text': str(i)}) for category in ('a', 'b', 'c') for i in range(4)]
        first = stratified_sample(rows, 3, 42)
        self.assertEqual(first, stratified_sample(rows, 3, 42))
        self.assertEqual({r['category'] for r in first}, {'a', 'b', 'c'})
        self.assertEqual(len(stratified_sample(rows, 0, 42)), len(rows))


if __name__ == '__main__':
    unittest.main()
