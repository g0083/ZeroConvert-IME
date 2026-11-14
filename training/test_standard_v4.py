"""Check twelve-layer optimizer grouping and dual-anchor gradient isolation."""

import tempfile
import unittest
from pathlib import Path

import torch
from peft import LoraConfig, TaskType, get_peft_model
from transformers import GPT2Config, GPT2LMHeadModel, TrainingArguments

from train_standard_v4 import StandardDualAnchorTrainer, layer_tier
from train_nano_v3 import build_tokenized_examples, disable_all_dropout
from benchmark_kana_kanji_models import (
    check_structural_integrity,
    compute_latency_metrics,
    compute_quantization_consistency,
    evaluate_model,
    mask_digits_for_model,
    restore_digits_from_model,
)
from build_colloquial_testset import (
    OUT_PATH as COLLOQUIAL_TESTSET_PATH,
    build_cases as build_colloquial_cases,
    validate_cases as validate_colloquial_cases,
)
from build_ime_realistic_testset import OUT_PATH as IME_TESTSET_PATH, build_cases, validate_cases


class _DummyTokenizer:
    eos_token_id = 3

    def encode(self, text, add_special_tokens=False):
        return [10 + ord(ch) % 50 for ch in text]


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

    def test_prior_copy_margin_with_distinct_prior_and_padding(self):
        torch.manual_seed(7)
        config = GPT2Config(vocab_size=600, n_layer=12, n_embd=12, n_head=2, n_positions=16,
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
            batch = {
                "input_ids": torch.tensor([[2, 10, 20, 20, 30, 3], [2, 10, 25, 3, 1, 1]]),
                "labels": torch.tensor([[-100, -100, 20, 20, 30, 3], [-100, -100, 25, 3, -100, -100]]),
            }
            loss = trainer.compute_loss(student, batch)
            self.assertTrue(torch.isfinite(loss))
            self.assertGreater(loss.item(), 0.0)
            loss.backward()
            self.assertTrue(all(
                torch.isfinite(p.grad).all()
                for p in student.parameters() if p.requires_grad and p.grad is not None
            ))

    def test_empty_context_prompt_emits_ee02_variant_for_train_only(self):
        tok = _DummyTokenizer()
        rows = [{"prompt": "\uee00シラン\uee01", "completion": "知らん"}]
        train_ex = build_tokenized_examples(rows, tok, max_length=64, is_train=True)
        val_ex = build_tokenized_examples(rows, tok, max_length=64, is_train=False)
        self.assertEqual(len(train_ex), 2)
        self.assertEqual(len(val_ex), 1)
        ee02_id = tok.encode("\uee02")[0]
        self.assertEqual(train_ex[1]["input_ids"][0], ee02_id)
        self.assertEqual(train_ex[1]["input_ids"][1:], train_ex[0]["input_ids"])

    def test_nam_digit_mask_and_restore(self):
        self.assertEqual(
            mask_digits_for_model("予算は１０００円", "１００えんのあめ"),
            ("予算はnam円", "namえんのあめ", ["100"]),
        )
        self.assertEqual(restore_digits_from_model("nam円の飴", ["100"]), "100円の飴")
        self.assertEqual(
            mask_digits_for_model("", "１がつ１にち"),
            ("", "namがつnamにち", ["1", "1"]),
        )
        self.assertEqual(restore_digits_from_model("nam月nam日", ["1", "1"]), "1月1日")
        self.assertEqual(
            restore_digits_from_model("SELECT name FROM customers", ["10"]),
            "SELECT name FROM customers",
        )
        self.assertEqual(
            restore_digits_from_model("SELECT nameです dynamic", ["10"]),
            "SELECT nameです dynamic",
        )
        self.assertEqual(restore_digits_from_model("nambonまとめて持つ", ["3"]), "3bonまとめて持つ")
        self.assertEqual(restore_digits_from_model("namxnamの行列", ["2", "3"]), "2x3の行列")

    def test_latency_and_sla_metrics(self):
        latencies = [10.0] * 94 + [35.0] * 3 + [55.0] * 2 + [120.0]
        m = compute_latency_metrics(latencies)
        self.assertEqual(m["p50_latency_ms"], 10.0)
        self.assertEqual(m["p95_latency_ms"], 35.0)
        self.assertEqual(m["p99_latency_ms"], 120.0)
        self.assertEqual(m["sla_over_30ms_count"], 6)
        self.assertEqual(m["sla_over_30ms_pct"], 6.0)
        self.assertEqual(m["sla_over_50ms_count"], 3)
        self.assertEqual(m["sla_over_50ms_pct"], 3.0)
        self.assertEqual(m["sla_over_100ms_count"], 1)
        self.assertEqual(m["sla_over_100ms_pct"], 1.0)

    def test_structural_integrity_checks(self):
        case_num = {"target": "3かいにあがる", "expected": "3階に上がる"}
        ok = check_structural_integrity("3階に上がる", case_num, ["3"], False)
        self.assertTrue(ok["intact"])
        self.assertTrue(ok["nam_restored_ok"])

        bad_nam = check_structural_integrity("nam階に上がる", case_num, ["3"], False)
        self.assertFalse(bad_nam["nam_restored_ok"])
        self.assertFalse(bad_nam["intact"])

        bad_repeat = check_structural_integrity("1月日", {"target": "1がつ1にち", "expected": "1月1日"}, ["1", "1"], False)
        self.assertFalse(bad_repeat["nam_restored_ok"])

        bad_pua = check_structural_integrity("明日\uee00晴れ", {"target": "あしたはれ", "expected": "明日晴れ"}, [], False)
        self.assertFalse(bad_pua["no_control_or_pua"])
        self.assertFalse(bad_pua["intact"])

        bad_len = check_structural_integrity("あ" * 40, {"target": "あした", "expected": "明日"}, [], False)
        self.assertFalse(bad_len["no_runaway_length"])
        self.assertFalse(bad_len["intact"])

        bad_empty = check_structural_integrity("", {"target": "あした", "expected": "明日"}, [], True)
        self.assertFalse(bad_empty["non_empty"])
        self.assertFalse(bad_empty["no_token_limit"])
        self.assertFalse(bad_empty["intact"])

    def test_evaluate_model_metrics_and_quantization_consistency(self):
        import json as _json

        cases = [
            {"id": 1, "category": "基本", "left_context": "なし", "target": "あしたはれる", "expected": "明日晴れる"},
            {"id": 2, "category": "数値", "left_context": "建物", "target": "3かいにあがる", "expected": "3階に上がる"},
            {"id": 3, "category": "境界", "left_context": "なし", "target": "あしたのよていをかくにんしたい", "expected": "明日の予定を確認したい"},
        ]

        class _FakeServer:
            def __init__(self, responses):
                self._responses = list(responses)
                self.server_exe = ""

            def start(self):
                pass

            def stop(self):
                pass

            def conversion_prompt(self, context, reading):
                return f"\uee02{context}\uee00{reading}\uee01"

            def complete(self, prompt):
                if prompt == "\uee02\uee00キョウハイイテンキデス\uee01":
                    return {"content": "今日はいい天気です", "stopped_limit": False}
                return {"content": self._responses.pop(0), "stopped_limit": False}

            def get_memory_mb(self):
                return {"rss_mb": 40.0, "peak_rss_mb": 50.0}

        temp_root = Path(__file__).resolve().parents[1] / "artifacts" / "checkpoints"
        with tempfile.TemporaryDirectory(dir=temp_root, prefix="bench-metric-test-") as tmp:
            fake_model = Path(tmp) / "fake.gguf"
            fake_model.write_bytes(b"0" * (2 * 1024 * 1024))
            res_q8 = evaluate_model(
                str(fake_model), 0, cases, n_gpu_layers=0, backend="cpu",
                server_factory=lambda *_: _FakeServer(["明日晴れる", "nam階に上がる", "明日の予定を確認したい"]),
            )
            res_q4 = evaluate_model(
                str(fake_model), 0, cases, n_gpu_layers=0, backend="cpu",
                server_factory=lambda *_: _FakeServer(["明日晴れる", "nam回に上がる", "明日の予定を確認したい"]),
            )
        self.assertEqual(res_q8["perfect_count"], 3)
        self.assertEqual(res_q8["rss_mb"], 40.0)
        self.assertEqual(res_q8["peak_rss_mb"], 50.0)
        self.assertEqual(res_q8["accuracy_per_file_mb"], 50.0)
        self.assertEqual(res_q8["accuracy_per_ram_mb"], 2.0)
        self.assertEqual(res_q8["structural_integrity_pct"], 100.0)
        self.assertEqual(res_q8["nam_restoration_pct"], 100.0)
        self.assertIn("short_4_8", res_q8["length_bucket_stats"])
        self.assertIn("precommit_15_22", res_q8["length_bucket_stats"])

        results = {"Nano v4 Q8": res_q8, "Nano v4 IQ4": res_q4}
        pairs = compute_quantization_consistency(results)
        self.assertIn("Nano v4 Q8 vs Nano v4 IQ4", pairs)
        qc = results["Nano v4 IQ4"]["quantization_consistency"]
        self.assertEqual(qc["exact_output_match_count"], 2)
        self.assertEqual(qc["q8_only_perfect_ids"], [2])
        self.assertEqual(qc["q4_only_perfect_ids"], [])
        self.assertEqual(len(_json.dumps(results)), len(_json.dumps(results)))

    def test_ime_realistic_testset_validity_and_isolation(self):
        self.assertTrue(IME_TESTSET_PATH.exists())
        cases = build_cases()
        validate_cases(cases)

    def test_colloquial_chat_testset_validity_and_isolation(self):
        self.assertTrue(COLLOQUIAL_TESTSET_PATH.exists())
        cases = build_colloquial_cases()
        validate_colloquial_cases(cases)


if __name__ == "__main__":
    unittest.main()
