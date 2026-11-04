import unittest
from corrector_encoding import encode_example


class Tokenizer:
    eos_token_id = 0

    def encode(self, text, add_special_tokens=False):
        return list(map(ord, text))


class EncodingTests(unittest.TestCase):
    def test_long_context_cannot_truncate_the_input_or_supervised_answer(self):
        row = encode_example(Tokenizer(), "\uee02" + "昔" * 100 + "直前\uee00オkセリ\uee01", "おかえり", 20)
        visible = "".join(chr(n) for n in row["input_ids"] if n)
        self.assertIn("直前\uee00オkセリ\uee01おかえり", visible)
        self.assertEqual(row["labels"][-5:], [ord(c) for c in "おかえり"] + [0])
        self.assertEqual(len(row["input_ids"]), 20)

    def test_too_long_target_is_rejected_instead_of_learning_a_partial_answer(self):
        with self.assertRaises(ValueError):
            encode_example(Tokenizer(), "\uee00" + "ア" * 20 + "\uee01", "あ" * 20, 30)


if __name__ == "__main__":
    unittest.main()
