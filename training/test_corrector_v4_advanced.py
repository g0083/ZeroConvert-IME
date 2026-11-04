"""Gradient isolation, target correction and error-only margins for advanced v4."""
import tempfile
import unittest

import torch
from peft import LoraConfig, TaskType, get_peft_model
from transformers import GPT2Config, GPT2LMHeadModel, TrainingArguments

from train_corrector_v4_advanced import DualAnchorCorrectorTrainer, corrected_logits
from train_nano_v3 import disable_all_dropout


class AdvancedCorrectorTests(unittest.TestCase):
    def test_target_correction_swaps_logits_and_preserves_correct_rows(self):
        scores = torch.tensor([[1., 5., 2.], [5., 1., 2.]])
        output = corrected_logits(scores, torch.tensor([2, 0]))
        self.assertEqual(output.tolist(), [[1., 2., 5.], [5., 1., 2.]])
        self.assertEqual(scores.tolist(), [[1., 5., 2.], [5., 1., 2.]])

    def test_dual_anchor_gradients_and_dora_optimizer_groups(self):
        torch.set_num_threads(2)
        model = get_peft_model(GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=6, n_head=2,
            n_embd=16, n_positions=32, eos_token_id=3)), LoraConfig(task_type=TaskType.CAUSAL_LM,
                r=4, lora_alpha=8, use_dora=True, target_modules=["c_attn", "c_proj", "c_fc"]))
        teacher = GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=2, n_head=2, n_embd=16, n_positions=32, eos_token_id=3))
        disable_all_dropout(model)
        disable_all_dropout(teacher)
        with tempfile.TemporaryDirectory() as directory:
            trainer = DualAnchorCorrectorTrainer(model=model, teacher_model=teacher,
                generation_rows=[], generation_tokenizer=None,
                args=TrainingArguments(output_dir=directory, learning_rate=1e-4, report_to="none", use_cpu=True))
            batch = {"input_ids": torch.tensor([[2, 5, 10, 20, 3], [2, 5, 15, 30, 3]]),
                     "labels": torch.tensor([[-100, -100, 10, 20, 3], [-100, -100, 15, 30, 3]]),
                     "clean_target": torch.tensor([True, False])}
            loss = trainer.compute_loss(model, batch)
            self.assertFalse(trainer.model_accepts_loss_kwargs)
            self.assertFalse(trainer.loss_is_scaled_for_ga)
            self.assertTrue(torch.isfinite(loss))
            loss.backward()
            self.assertTrue(any(p.grad is not None and p.grad.abs().sum() > 0 for p in model.parameters() if p.requires_grad))
            self.assertTrue(all(p.grad is None for p in teacher.parameters()))
            self.assertTrue(all(p.grad is None for p in model.parameters() if not p.requires_grad))
            optimizer = trainer.create_optimizer()
            by_id = {id(p): g for g in optimizer.param_groups for p in g["params"]}
            self.assertEqual(len(by_id), sum(p.requires_grad for p in model.parameters()))
            for name, parameter in model.named_parameters():
                if parameter.requires_grad and "lora_magnitude_vector" in name:
                    self.assertEqual(by_id[id(parameter)]["weight_decay"], 0.)
            empty = {**batch, "labels": torch.full_like(batch["labels"], -100)}
            self.assertEqual(trainer.compute_loss(model, empty).item(), 0.)


if __name__ == "__main__":
    unittest.main()
