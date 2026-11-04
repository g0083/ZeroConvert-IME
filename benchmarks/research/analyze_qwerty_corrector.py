"""Paired source-group analysis of the frozen native QWERTY comparison."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import random
import statistics

from dataset_isolation import load_jsonl, sha256
from evaluate_v4_repair_comparison import ReadingVerifier
from qwerty_corrector_policy import summary, write_json


def accepted(row, output):
    return output is not None and output in [row["expected"], *(row.get("acceptable") or [])]


def group_values(rows, report):
    groups = defaultdict(lambda: [0, 0, 0])  # TP, FP, repair cases
    by_id = {r["id"]: r for r in report["cases"]}
    for row in rows:
        result = by_id[row["id"]]
        values = groups[row["source_group"]]
        repair = row["expected_action"] == "repair"
        correct = repair and accepted(row, result["correction"])
        values[0] += int(correct)
        values[1] += int(result["correction"] is not None and not correct)
        values[2] += int(repair)
    return dict(groups)


def fscore(values):
    tp, fp, total = values
    return 1.25*tp/(1.25*tp + fp + .25*(total-tp)) if total+fp else 0.


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--comparison-dir", type=Path, required=True)
    p.add_argument("--out", type=Path)
    a = p.parse_args()
    out = a.out or a.comparison_dir / "group-analysis.json"
    if out.exists():
        raise FileExistsError(out)
    rows = load_jsonl(a.comparison_dir / "native-test-cases.jsonl")
    reports = {name: json.loads((a.comparison_dir / f"native-test-{name}.json").read_text(encoding="utf-8"))
        for name in ("previous-v4", "qwerty-v4")}
    before, after = reports.values()
    # Completion metadata can differ; model content must be identical.
    if [(r["id"], r["normal_response"]["content"]) for r in before["cases"]] != [(r["id"], r["normal_response"]["content"]) for r in after["cases"]]:
        raise ValueError("Independent normal-model candidate contents changed")
    groups = {name: group_values(rows, report) for name, report in reports.items()}
    keys = sorted(groups["previous-v4"])
    if keys != sorted(groups["qwerty-v4"]):
        raise ValueError("Source groups differ")
    rng = random.Random(42)
    deltas = []
    for _ in range(5000):
        sampled = rng.choices(keys, k=len(keys))
        scores = [fscore([sum(groups[name][k][i] for k in sampled) for i in range(3)]) for name in reports]
        deltas.append(scores[1]-scores[0])
    deltas.sort()
    result = {"normal_candidate_contents_identical": True, "groups": len(keys),
        "bootstrap": {"unit": "source_group", "draws": 5000, "seed": 42,
            "paired_micro_f0_5_difference_95_percentile_interval": [deltas[125], deltas[4874]],
            "scope": "Synthetic fixed cohort only; not empirical user frequency or IME safety"}, "models": {}}
    dictionary = Path("ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin")
    verifier = ReadingVerifier(dictionary)
    result["reading_diagnostic"] = {"dictionary_sha256": sha256(dictionary),
        "scope": "Dictionary reading agreement only; homophones/semantic correctness not adjudicated; frozen surface scores unchanged"}
    for name, report in reports.items():
        values = groups[name]
        kinds = {}
        for kind in sorted({r["typo_type"] for r in rows}):
            subset = [r for r in rows if r["typo_type"] == kind]
            ids = {r["id"] for r in subset}
            kinds[kind] = summary({"cases": [r for r in report["cases"] if r["id"] in ids]}, subset)
        by_id = {r["id"]: r for r in report["cases"]}
        reading_agreement = sum(bool(by_id[r["id"]]["correction"]) and verifier.matches(by_id[r["id"]]["correction"], r["target_reading"])
            for r in rows if r["expected_action"] == "repair")
        result["models"][name] = {"report_sha256": sha256(a.comparison_dir / f"native-test-{name}.json"),
            "macro_source_group_f0_5": statistics.mean(fscore(v) for v in values.values()),
            "macro_repair_recall": statistics.mean(v[0]/v[2] for v in values.values() if v[2]),
            "repair_dictionary_reading_agreement": reading_agreement,
            "by_typo_type": kinds}
    write_json(out, result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
