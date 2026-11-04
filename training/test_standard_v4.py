"""Check twelve-layer optimizer grouping and dual-anchor gradient isolation."""

import tempfile
import unittest
from pathlib import Path

import torch
from peft import LoraConfig, TaskType, get_peft_model
from transformers import GPT2Config, GPT2LMHeadModel, TrainingArguments

from train_standard_v4 import StandardDualAnchorTrainer, layer_tier
from train_nano_v3 import disable_all_dropout


class StandardV4Tests(unittest.TestCase):
    def test_all_twelve_layers_have_expected_tiers(self):
        self.assertEqual(
            [layer_tier(f"base_model.model.transformer.h.{i}.mlp.c_fc.lora_A.default.weight") for i in range(12)],
            ["low"] * 4 + ["mid"] * 4 + ["high"] * 4,
        )

    def test_loss_only_trains_adapter_and_optimizer_preserves_magnitudes(self):
        torch.manual_seed(42)
        config = GPT2Config(vocab_size=6000, n_layer=12, n_embd=12, n_head=2, n_positions=16,
                            bos_token_id=2, eos_token_id=3, pad_token_id=1)
        student = get_peft_model(GPT2LMHeadModel(config), LoraConfig(
            task_type=TaskType.CAUSAL_LM, r=2, lora_alpha=4,
            target_modules=["c_attn", "c_proj", "c_fc"], use_dora=True,
        ))
        teacher = GPT2LMHeadModel(config).eval().requires_grad_(False)
        disable_all_dropout(student)
        temp_root = Path(__file__).resolve().parents[1] / "artifacts" / "checkpoints"
        with tempfile.TemporaryDirectory(dir=temp_root, prefix="standard-v4-test-") as directory:
            trainer = StandardDualAnchorTrainer(
                teacher_model=teacher, model=student,
                args=TrainingArguments(output_dir=directory, learning_rate=1e-4, weight_decay=0.01,
                                       report_to="none", use_cpu=True),
            )
            batch = {"input_ids": torch.tensor([[2, 10, 20, 10, 3]]),
                     "labels": torch.tensor([[-100, -100, 20, 10, 3]])}
            loss = trainer.compute_loss(student, batch)
            self.assertTrue(torch.isfinite(loss))
            loss.backward()
            self.assertTrue(any(p.grad is not None and p.grad.abs().sum() > 0
                                for p in student.parameters() if p.requires_grad))
            self.assertTrue(all(p.grad is None for p in teacher.parameters()))
            self.assertTrue(all(p.grad is None for p in student.parameters() if not p.requires_grad))
            optimizer = trainer.create_optimizer()
            by_id = {id(p): group for group in optimizer.param_groups for p in group["params"]}
            trainable = [p for p in student.parameters() if p.requires_grad]
            self.assertEqual(len(by_id), len(trainable))
            for name, parameter in student.named_parameters():
                if parameter.requires_grad:
                    group = by_id[id(parameter)]
                    multiplier = {"low": 0.4, "mid": 1.0, "high": 1.3}[layer_tier(name)]
                    self.assertAlmostEqual(group["lr"], 1e-4 * multiplier)
                    if "lora_magnitude_vector" in name:
                        self.assertEqual(group["weight_decay"], 0.0)
            empty = {"input_ids": batch["input_ids"], "labels": torch.full_like(batch["labels"], -100)}
            self.assertEqual(trainer.compute_loss(student, empty).item(), 0.0)


if __name__ == "__main__":
    unittest.main()
