# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

import torch
from transformers import LlamaConfig, LlamaForCausalLM

from origin_target_loss import target_balanced_ce
from train_origin import balanced_ce


class TargetLossTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(4)
        torch.manual_seed(42)
        self.model = LlamaForCausalLM(LlamaConfig(vocab_size=64, hidden_size=32, intermediate_size=64,
            num_hidden_layers=2, num_attention_heads=4, num_key_value_heads=2,
            tie_word_embeddings=True, attention_dropout=0, pad_token_id=3, eos_token_id=2)).train()
        self.ids = torch.tensor([[1, 4, 5, 2, 3, 3], [1, 6, 7, 8, 9, 2], [1, 10, 11, 12, 2, 3]])
        self.labels = torch.tensor([[-100, -100, 5, 2, -100, -100],
                                    [-100, -100, -100, 8, 9, 2],
                                    [-100, -100, -100, -100, 2, -100]])
        self.mask = self.ids.ne(3)

    def test_variable_target_lengths_padding_eos_and_every_parameter_gradient(self):
        reference = copy.deepcopy(self.model)
        full_loss = balanced_ce(reference(input_ids=self.ids, attention_mask=self.mask, use_cache=False).logits,
                                self.labels)
        selected_loss = target_balanced_ce(self.model, self.ids, self.mask, self.labels)
        torch.testing.assert_close(selected_loss, full_loss, rtol=1e-6, atol=1e-6)
        full_loss.backward()
        selected_loss.backward()
        for (name, actual), (expected_name, expected) in zip(self.model.named_parameters(), reference.named_parameters()):
            self.assertEqual(name, expected_name)
            torch.testing.assert_close(actual.grad, expected.grad, rtol=2e-5, atol=2e-6, msg=name)
        # Token4 occurs only in an ignored prompt position and is never a target.
        # Its embedding must still learn through its effect on later tokens.
        self.assertGreater(self.model.model.embed_tokens.weight.grad[4].abs().sum().item(), 0)

    def test_missing_supervision_and_malformed_shapes_rejected(self):
        labels = self.labels.clone()
        labels[0].fill_(-100)
        with self.assertRaises(ValueError):
            target_balanced_ce(self.model, self.ids, self.mask, labels)
        with self.assertRaises(ValueError):
            target_balanced_ce(self.model, self.ids, self.mask, self.labels[:, :-1])

    def test_eos_is_not_removed_from_the_loss(self):
        first = target_balanced_ce(self.model, self.ids, self.mask, self.labels)
        changed = self.labels.clone()
        changed[changed == 2] = 20
        second = target_balanced_ce(self.model, self.ids, self.mask, changed)
        self.assertNotEqual(first.item(), second.item())


if __name__ == '__main__':
    unittest.main()
