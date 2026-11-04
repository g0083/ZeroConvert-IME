"""Audit paired Standard v3/v4 evaluations and list both fixes and regressions."""

import json
from pathlib import Path

from benchmark_kana_kanji_models import sha256


def main():
    root = Path("artifacts/results")
    pairs = {}
    for variant, prefix in (("F16", "standard_v4_transfer"), ("Q8", "standard_v4_transfer_quants"),
                            ("IQ4", "standard_v4_transfer_quants")):
        summary = {"models": {}, "changes": {}}
        for suffix, dataset in (("230", "data/kana_kanji_llm_testset_5x.jsonl"),
                                ("250hard", "data/kana_kanji_hard_context_testset.jsonl")):
            results = json.loads((root / f"{prefix}_{suffix}.json").read_text(encoding="utf-8"))
            before, after = (results[f"Standard v{version} {variant}"] for version in (3, 4))
            if before["protocol"] != after["protocol"] or before["runtime_sha256"] != after["runtime_sha256"]:
                raise ValueError("Runtime or evaluation settings differ")
            for result in (before, after):
                if result["testset_sha256"] != sha256(dataset):
                    raise ValueError("Evaluation data changed")
                if result["model_sha256"] != sha256(result["model_path"]):
                    raise ValueError("Model changed after evaluation")
                if result["total_cases"] != len(result["details"]):
                    raise ValueError("Incomplete results")
            fixes, regressions = [], []
            for old, new in zip(before["details"], after["details"], strict=True):
                if any(old[field] != new[field] for field in ("id", "target", "expected", "left_context")):
                    raise ValueError("Case alignment differs")
                change = {"id": old["id"], "target": old["target"], "expected": old["expected"],
                          "before": old["output"], "after": new["output"]}
                if old["score"] != 2 and new["score"] == 2:
                    fixes.append(change)
                if old["score"] == 2 and new["score"] != 2:
                    regressions.append(change)
            summary["changes"][suffix] = {"fixes": fixes, "regressions": regressions}
            for version, result in (("v3", before), ("v4", after)):
                summary["models"].setdefault(version, {})[suffix] = {
                    k: result[k] for k in ("perfect_count", "total_cases", "size_mb", "avg_latency_ms",
                                          "p95_latency_ms", "truncated_count", "model_sha256", "category_stats")
                }
        pairs[variant] = summary
        for version, values in summary["models"].items():
            print(variant, version, sum(v["perfect_count"] for v in values.values()),
                  "truncated=", sum(v["truncated_count"] for v in values.values()))
    target = root / "standard_v4_transfer_summary.json"
    target.write_text(json.dumps(pairs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved: {target}")


if __name__ == "__main__":
    main()
