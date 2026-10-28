"""Regression tests for evaluation leakage and source-group split boundaries."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from dataset_isolation import normalize, separated_rows, source_keys, validate_rows
from build_separated_datasets import build
import build_separated_datasets as builder
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training"))
from dataset_guard import checked_datasets, record_training_completed


def row(reading, answer, **extra):
    return {"prompt": f"\uee00{reading}\uee01", "completion": answer, **extra}


class IsolationTests(unittest.TestCase):
    def test_width_kana_and_space_cannot_bypass_reservation(self):
        self.assertEqual(normalize(" ｶﾀｶﾅ　"), normalize("かたかな"))
        train, val, excluded = separated_rows([row("カタカナ", "片仮名")], {"かたかな"})
        self.assertEqual((len(train), len(val), len(excluded)), (0, 0, 1))

    def test_context_and_mutation_siblings_are_reserved_transitively(self):
        rows = [
            row("ごかくにん", "ご確認", target_reading="ごかくにん"),
            row("ごかくにｎ", "ご確認", left_context="別の文脈"),
            row("ごかくにｎ", "ご確認ください"),
        ]
        train, val, excluded = separated_rows(rows, {"ごかくにん"})
        self.assertEqual(len(excluded), 3)
        self.assertFalse(train or val)

    def test_acceptable_prediction_and_full_sentence_are_keys(self):
        keys = source_keys({"partial_input": "今日は", "full_sentence": "今日は晴れです。",
                            "acceptable_continuations": ["晴れです。"]})
        self.assertIn("晴れです。", keys)
        self.assertIn("今日は晴れです。", keys)

    def test_derived_category_is_excluded_even_without_matching_text(self):
        _, _, excluded = separated_rows([row("未知", "例", category="testset_clean")], set())
        self.assertEqual(len(excluded), 1)

    def test_grouped_split_is_order_independent_and_has_no_cross_keys(self):
        rows = [row(f"reading{i}", f"answer{i}", target_reading=f"source{i // 2}") for i in range(200)]
        tr, va, _ = separated_rows(rows, set())
        validate_rows(tr, va, set())
        tr2, va2, _ = separated_rows(list(reversed(rows)), set())
        self.assertEqual({r["prompt"] for r in tr}, {r["prompt"] for r in tr2})
        self.assertEqual({r["prompt"] for r in va}, {r["prompt"] for r in va2})

    def test_guard_rejects_leakage_and_empty_validation(self):
        with self.assertRaisesRegex(ValueError, "isolation failed"):
            validate_rows([row("誤入力", "正解")], [row("別入力", "正解")], set())
        with self.assertRaisesRegex(ValueError, "isolation failed"):
            validate_rows([row("入力", "正解")], [row("別入力", "別解")], {"入力"})
        with self.assertRaises(ValueError):
            validate_rows([row("入力", "正解")], [], set())

    def test_training_guard_stops_before_creating_output_and_records_valid_inputs(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as tmp:
            root = Path(tmp)
            train, val = root / "train.jsonl", root / "val.jsonl"
            train.write_text(json.dumps(row("入力", "正解")) + "\n", encoding="utf-8")
            val.write_text(json.dumps(row("別入力", "別解")) + "\n", encoding="utf-8")
            with patch("dataset_guard.evaluation_keys", return_value={"入力"}):
                with self.assertRaises(ValueError):
                    checked_datasets(train, val, root / "model")
            self.assertFalse((root / "model").exists())
            with patch("dataset_guard.evaluation_keys", return_value=set()), patch("dataset_guard.EVALUATION_FILES", (train,)):
                tr, va = checked_datasets(train, val, root / "model")
            self.assertEqual((len(tr), len(va)), (1, 1))
            provenance = json.loads((root / "model/dataset-provenance.json").read_text(encoding="utf-8"))
            self.assertEqual(provenance["checks"]["train_evaluation_shared_keys"], 0)
            self.assertEqual(len(provenance["train"]["sha256"]), 64)
            self.assertFalse(provenance["training_completed"])
            with patch("dataset_guard.evaluation_keys", return_value=set()), patch("dataset_guard.EVALUATION_FILES", (train,)):
                with self.assertRaises(FileExistsError):
                    checked_datasets(train, val, root / "model")
            record_training_completed(root / "model")
            self.assertTrue(json.loads((root / "model/dataset-provenance.json").read_text(encoding="utf-8"))["training_completed"])

    def test_build_is_reproducible_preserves_inputs_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as tmp:
            root = Path(tmp)
            source = root / "source.jsonl"
            rows = [row(f"input{i}", f"answer{i}", target_reading=f"source{i // 2}") for i in range(200)]
            source.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            evaluation = root / "eval.jsonl"
            evaluation.write_text('{"target":"input0","expected":"answer0"}\n', encoding="utf-8")
            before = source.read_bytes()
            with patch.object(builder, "ROOT", root), patch.object(builder, "SOURCES", (("source.jsonl", "typo"), ("source.jsonl", "distilled"))), patch.object(builder, "EVALUATION_FILES", (evaluation,)), patch.object(builder, "evaluation_keys", return_value={"input0", "answer0"}):
                first = build(root / "one")
                second = build(root / "two")
                self.assertEqual(first, second)
                self.assertEqual(first["excluded_rows"], 4)
                with self.assertRaises(FileExistsError):
                    build(root / "one")
            self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
