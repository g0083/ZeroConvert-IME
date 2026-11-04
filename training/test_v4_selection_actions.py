"""Normal-input abstention is distinct from an emitted incorrect repair."""
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from v4_generation_selection import assess


class SelectionTests(unittest.TestCase):
    def test_normal_actions_are_separate(self):
        answers = [{'action': 'keep'}, {'action': 'abstain'},
                   {'action': 'emit', 'text': 'べつのよみ'}, {'action': 'invalid'}]
        codec = SimpleNamespace(metadata={'repair_output': 'reading'}, prompt=lambda row: [],
                                decode_answer=lambda output, task: answers[output])
        model = SimpleNamespace(eval=lambda: None, train=lambda: None)
        cases = [{'id': str(i), 'task': 'repair', 'selection_group': 'repair_keep',
                  'input': {'reading': 'かな'}, 'target': {'action': 'keep'}} for i in range(4)]
        with patch('v4_generation_selection.hf_generate', return_value=list(range(4))):
            result = assess(model, codec, cases, 'cpu')['summary']
        self.assertEqual(result['normal_changed'], 1)
        self.assertEqual(result['normal_abstained'], 1)
        self.assertEqual(result['invalid'], 1)


if __name__ == '__main__':
    unittest.main()
