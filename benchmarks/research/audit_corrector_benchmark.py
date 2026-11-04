"""Audit frozen correction cases and separate raw generations from policy gates.

Never changes questions, acceptable answers or performance results. An oracle
report is diagnostic and must not be described as measured model accuracy.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path

from dataset_isolation import load_jsonl, normalize, sha256


def distance(a, b):
    a, b = normalize(a), normalize(b)
    dp = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        dp[i][0] = i
    for j in range(len(b) + 1):
        dp[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            dp[i][j] = min(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+(a[i-1] != b[j-1]))
            if i > 1 and j > 1 and a[i-1] == b[j-2] and a[i-2] == b[j-1]:
                dp[i][j] = min(dp[i][j], dp[i-2][j-2]+1)
    return dp[-1][-1]


def wilson(errors, total):
    if not total:
        return None
    z = 1.959963984540054
    p = errors / total
    scale = 1 + z*z/total
    center = (p + z*z/(2*total)) / scale
    half = z * math.sqrt(p*(1-p)/total + z*z/(4*total*total)) / scale
    return [max(0., center-half), min(1., center+half)]


def accepted(row, output):
    return output is not None and output in [row["expected"], *(row.get("acceptable") or [])]


def analyze(cases, model_reports, oracle):
    typo = [r for r in cases if r.get("is_clean") is not True]
    clean = [r for r in cases if r.get("is_clean") is True]
    summary = {
        "total": len(cases), "typo": len(typo), "clean": len(clean),
        "unique_canonical_readings": len({normalize(r["target_reading"]) for r in cases}),
        "unique_clean_readings": len({normalize(r["target_reading"]) for r in clean}),
        "unique_context_reading_pairs": len({(r.get("context", ""), normalize(r["target_reading"])) for r in cases}),
        "type_counts": dict(Counter(r.get("typo_type") for r in cases)),
        "typo_reading_distance": dict(Counter(distance(r["input_reading"], r["target_reading"]) for r in typo)),
        "raw_keystroke_provenance_missing": sum(not r.get("observed_keys") and not r.get("raw_keys") for r in cases),
        "has_acceptable_answers": sum(bool(r.get("acceptable")) for r in cases),
        "mixed_or_non_kana_gold_reading_ids": [r["id"] for r in cases if any(not ("ぁ" <= c <= "ゖ" or c == "ー") for c in normalize(r["target_reading"]))],
        "prolonged_label_without_long_mark_in_source": [r["id"] for r in cases if r.get("typo_type") == "prolonged" and "ー" not in r["target_reading"]],
        "clean_flag_inconsistent_with_reading": [r["id"] for r in cases if (normalize(r["input_reading"]) == normalize(r["target_reading"])) != (r.get("is_clean") is True)],
    }
    result = {"dataset": summary, "models": {}}
    for name, report in model_reports.items():
        if report.get("oracle", False):
            raise ValueError("Oracle results cannot be counted as measured model accuracy")
        rows = report["cases"]
        if [r["id"] for r in rows] != [r["id"] for r in cases]:
            raise ValueError("Model report and audited cases must have identical case order")
        t = [r for r in rows if r["is_clean"] is not True]
        c = [r for r in rows if r["is_clean"] is True]
        correct = sum(accepted(r, r["correction"]) for r in t)
        wrong = sum(r["correction"] is not None and not accepted(r, r["correction"]) for r in t)
        clean_wrong = sum(r["correction"] is not None and not accepted(r, r["correction"]) for r in c)
        raw_gold = [r for r in t if any(call.get("response") and accepted(r, call["response"].get("content")) for call in r["calls"])]
        by_group = defaultdict(list)
        for row in t:
            by_group[normalize(row["expected_reading"])].append(accepted(row, row["correction"]))
        result["models"][name] = {
            "correct_suggestions": correct, "wrong_suggestions": wrong,
            "typo_recall": correct / len(t),
            "typo_suggestion_precision": correct / (correct+wrong) if correct+wrong else None,
            "clean_wrong": clean_wrong, "clean_total": len(c),
            "clean_wrong_wilson_95": wilson(clean_wrong, len(c)),
            "auto_applied_total": sum(r["auto_apply"] for r in rows),
            "auto_applied_unique_inputs": len({r["input"] for r in rows if r["auto_apply"]}),
            "wrong_auto_apply": sum(r["auto_apply"] and not accepted(r, r["correction"]) for r in rows),
            "raw_gold_seen": len(raw_gold),
            "raw_gold_without_gold_suggestion_ids": [r["id"] for r in raw_gold if not accepted(r, r["correction"])],
            "typo_no_model_call": sum(not r["calls"] for r in t),
            "source_macro_recall": sum(sum(v)/len(v) for v in by_group.values()) / len(by_group),
            "case_level_f0_5_including_clean_false_proposals": (1.25 * correct / (1.25 * correct + .25 * (len(t)-correct) + wrong + clean_wrong)) if correct+wrong+clean_wrong else 0.,
        }
    if oracle is not None:
        if oracle.get("oracle") is not True:
            raise ValueError("Expected an explicitly marked oracle report")
        rows = [r for r in oracle["cases"] if r["is_clean"] is not True]
        rejected = [r for r in rows if not accepted(r, r["correction"])]
        def reason(r):
            d = r.get("gold_reading_distance")
            if d is None:
                return "dictionary_reading_unavailable"
            if d == 0:
                return "same_reading_not_a_typo"
            if d > 2:
                return "reading_distance_gt2"
            return "other_guard_or_fixed_route"
        result["oracle"] = {"gold_surface_reached": len(rows)-len(rejected), "typo_total": len(rows),
            "dictionary_gt2_but_supplied_gold_reading_within2": sum(r.get("gold_reading_distance") is not None and r["gold_reading_distance"]>2 and distance(r["input"], r["expected_reading"])<=2 for r in rejected),
            "rejected_reason_clues": dict(Counter(reason(r) for r in rejected)),
            "rejected": [{k: r.get(k) for k in ("id", "input", "expected", "expected_reading", "gold_reading_distance", "correction")} | {"reason_clue": reason(r)} for r in rejected],
            "scope": "Perfect expected surface through existing policy; reason clues are not an execution trace; no model performance/latency claim."}
    return result


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cases", type=Path, default=Path("data/typo_benchmark_300.jsonl"))
    p.add_argument("--oracle", type=Path)
    p.add_argument("--model", action="append", default=[], help="NAME=JSON report")
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    model_paths = dict(spec.split("=", 1) for spec in args.model)
    result = analyze(load_jsonl(args.cases), {name: json.loads(Path(path).read_text(encoding="utf-8")) for name, path in model_paths.items()},
                     json.loads(args.oracle.read_text(encoding="utf-8")) if args.oracle else None)
    result["source_hashes"] = {str(p): sha256(p) for p in [args.cases, Path(__file__), *map(Path, model_paths.values()), *([args.oracle] if args.oracle else [])]}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "source_hashes"}, ensure_ascii=False, indent=2))
