# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from adapt_origin import task_sampling_weights, transfer_signature


class AdaptationTests(unittest.TestCase):
    def test_label_metadata_is_not_a_new_input(self):
        normal = {'id': 'a', 'task': 'repair_qwerty', 'input': {'raw': 'kaigi', 'pending': '', 'ctx': ''},
                  'target': {'action': 'keep', 'text': ''}, 'category': 'clean'}
        conservative = {**normal, 'original_target': normal['target'],
                        'target': {'action': 'abstain', 'text': ''}, 'category': 'ambiguous-observed-raw'}
        self.assertEqual(transfer_signature(normal, 'train'), transfer_signature(conservative, 'train'))
        with self.assertRaises(ValueError):
            transfer_signature(conservative, 'val')
        changed_input = copy.deepcopy(conservative)
        changed_input['input']['ctx'] = 'Different source text'
        self.assertNotEqual(transfer_signature(normal, 'train'), transfer_signature(changed_input, 'train'))

    def test_nonconservative_relabeling_is_rejected(self):
        row = {'target': {'action': 'emit', 'text': 'べつ'}, 'original_target': {'action': 'keep', 'text': ''}}
        with self.assertRaises(ValueError):
            transfer_signature(row, 'train')

    def test_sampling_weights_apply_to_tasks_not_row_counts(self):
        rows = [{'task': 'convert'}, {'task': 'convert'}, {'task': 'repair_qwerty'}]
        self.assertEqual(task_sampling_weights(rows, {'convert': .5, 'repair_qwerty': .5}), [.25, .5, 1.0])
        for invalid in ({'missing': 1}, {'convert': -1}, {'convert': 0}, {'convert': float('nan')}):
            with self.assertRaises(ValueError):
                task_sampling_weights(rows, invalid)


if __name__ == '__main__':
    unittest.main()
