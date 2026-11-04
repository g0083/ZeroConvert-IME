import unittest
from qwerty_input_contract import model_prompt


class ContractTests(unittest.TestCase):
    def test_gold_fields_cannot_change_prompt(self):
        observation = {"observed_keys": "siryouwookurimssu", "input_state": "append", "cursor_key_offset": 17}
        prompt = "\uee02会議の\uee00シリョウヲオクリmッス\uee01"
        expected = model_prompt(prompt, observation, "raw-state")
        poisoned = {**observation, "expected": "secret", "canonical_keys": "secret", "key_edit": {"position": 42}, "expected_action": "repair", "typo_type": "secret"}
        self.assertEqual(expected, model_prompt(prompt, poisoned, "raw-state"))
        self.assertNotIn("secret", expected)
        self.assertEqual(model_prompt(prompt, poisoned, "kana"), prompt)

    def test_observable_state_changes_model_input(self):
        prompt = "\uee00カイギ\uee01"
        row = {"observed_keys": "kaigi", "input_state": "append", "cursor_key_offset": 5}
        self.assertNotEqual(model_prompt(prompt, row, "raw-state"), model_prompt(prompt, {**row, "input_state": "backspace"}, "raw-state"))
        self.assertIn('"keys":null', model_prompt(prompt, {}, "raw-state"))
        with self.assertRaises(ValueError):
            model_prompt(prompt, {**row, "cursor_key_offset": 6}, "raw-state")


if __name__ == "__main__":
    unittest.main()
