# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from v4_repair_edits import repair_edit_annotations


class LabelCodec:
    metadata = {'repair_emit_marker': True}
    eos_id = 3

    def text(self, text):
        return list(map(ord, text))

    def observed_reading(self, raw, pending):
        return raw


def row(observed, reference, action='emit'):
    return {'task': 'repair', 'input': {'raw': observed, 'pending': ''},
            'target': {'action': action, 'text': reference}}


class EditLabelTests(unittest.TestCase):
    def test_missing_character_supervises_insertion_against_wrong_copy(self):
        self.assertEqual(repair_edit_annotations(LabelCodec(), row('あがとう', 'ありがとう')), [(2, ord('が'))])

    def test_extra_character_supervises_boundary_and_eos(self):
        self.assertEqual(repair_edit_annotations(LabelCodec(), row('ありっがとう', 'ありがとう')), [(3, ord('っ'))])
        self.assertEqual(repair_edit_annotations(LabelCodec(), row('ありがとうk', 'ありがとう')), [(6, ord('k'))])

    def test_correct_copies_and_keep_are_never_penalized(self):
        original = row('ありがとう', 'ありがとう')
        before = copy.deepcopy(original)
        self.assertEqual(repair_edit_annotations(LabelCodec(), original), [])
        self.assertEqual(original, before)
        self.assertEqual(repair_edit_annotations(LabelCodec(), row('ありがとう', '', 'keep')), [])


if __name__ == '__main__':
    unittest.main()
