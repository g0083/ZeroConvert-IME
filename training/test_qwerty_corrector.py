import os
from pathlib import Path
import random
import tempfile
import unittest

import torch
from peft import LoraConfig, get_peft_model
from transformers import GPT2Config, GPT2LMHeadModel, TrainingArguments

from build_qwerty_corrector import mutations, orthographic_alternatives, pending, split_name
from native_qwerty import NativeQwerty
from train_nano_v3 import disable_all_dropout
from train_qwerty_corrector import QwertyTrainer


class QwertyTests(unittest.TestCase):
    def test_every_synthetic_typo_is_a_recorded_key_edit(self):
        original = "siryouwookurimasu"
        kinds = set()
        for keys, kind, edit in mutations(original, random.Random(42)):
            kinds.add(kind)
            i, before, after = edit["position"], edit["before"], edit["after"]
            self.assertEqual(original[i:i+len(before)], before)
            self.assertEqual(keys, original[:i] + after + original[i+len(before):])
        self.assertEqual(kinds, {"adjacent", "deletion", "duplication", "transposition", "missing_vowel"})

    def test_homophones_are_not_okurigana_alternatives(self):
        self.assertIn("貸し出しをする", orthographic_alternatives("貸出しをする"))
        self.assertEqual(orthographic_alternatives("思い"), [])
        self.assertEqual(pending("かn"), "n")
        self.assertEqual(pending("かん"), "")
        self.assertEqual(split_name("train", "any"), "train")

    @unittest.skipUnless(os.name == "nt" and Path("ZeroConvertIME-windows/azookey-server.dll").exists(), "Windows native DLL required")
    def test_native_n_nn_and_deletion_reset(self):
        native = NativeQwerty()
        self.assertEqual(native.decode("n"), "n")
        self.assertEqual(native.decode("nn"), "ん")
        self.assertEqual(native.trace("kan"), ["k", "か", "かn"])
        self.assertEqual(native.decode("ka"), "か")
        native.clear()
        self.assertEqual(native.take(native.dll.GetRawInput()), "")

    def test_qwerty_loss_gradients_and_empty_supervision(self):
        torch.set_num_threads(2)
        model = get_peft_model(GPT2LMHeadModel(GPT2Config(vocab_size=6000, n_layer=2, n_head=2,
            n_embd=16, n_positions=32, eos_token_id=3)), LoraConfig(r=4, lora_alpha=8,
            use_dora=True, target_modules=["c_attn", "c_proj", "c_fc"]))
        disable_all_dropout(model)
        with tempfile.TemporaryDirectory() as directory:
            trainer = QwertyTrainer(model=model, fixture={}, tokenizer_for_generation=None, baseline={},
                args=TrainingArguments(output_dir=directory, report_to="none", use_cpu=True))
            batch = {"input_ids": torch.tensor([[2, 10, 20, 3]]), "labels": torch.tensor([[-100, -100, 20, 3]])}
            loss = trainer.compute_loss(model, batch)
            self.assertTrue(torch.isfinite(loss))
            loss.backward()
            self.assertTrue(any(p.grad is not None and p.grad.abs().sum() > 0 for p in model.parameters() if p.requires_grad))
            self.assertTrue(all(p.grad is None for p in model.parameters() if not p.requires_grad))
            self.assertEqual(trainer.compute_loss(model, {**batch, "labels": torch.full_like(batch["labels"], -100)}).item(), 0.)

    def test_preservation_anchor_uses_frozen_base_agreed_tokens(self):
        torch.manual_seed(42)
        model = get_peft_model(GPT2LMHeadModel(GPT2Config(vocab_size=64, n_layer=1, n_head=2,
            n_embd=16, n_positions=16, bos_token_id=2, eos_token_id=3)),
            LoraConfig(r=4, lora_alpha=8, target_modules=["c_attn", "c_proj"]))
        disable_all_dropout(model)
        with torch.no_grad():
            for name, value in model.named_parameters():
                if "lora_B" in name:
                    value.normal_(0., .5)
        ids = torch.tensor([[2, 10, 20, 30]])
        with torch.no_grad(), model.disable_adapter():
            gold = model(ids).logits[:, :-1].argmax(-1)
        labels = torch.cat([torch.full((1, 1), -100), gold], dim=1)
        batch = {"input_ids": ids, "labels": labels, "preserve": torch.tensor([True])}
        with tempfile.TemporaryDirectory() as directory:
            trainer = QwertyTrainer(model=model, fixture={}, tokenizer_for_generation=None, baseline={},
                args=TrainingArguments(output_dir=directory, report_to="none", use_cpu=True))
            without = trainer.compute_loss(model, batch).detach()
            trainer.clean_anchor = 1.
            anchored = trainer.compute_loss(model, batch)
            self.assertGreater(anchored.item(), without.item())
            anchored.backward()
            self.assertTrue(all(p.grad is None for p in model.parameters() if not p.requires_grad))
            batch["preserve"] = torch.tensor([False])
            self.assertAlmostEqual(trainer.compute_loss(model, batch).item(), without.item(), places=5)


if __name__ == "__main__":
    unittest.main()
