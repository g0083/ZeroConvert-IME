"""Paired group bootstrap for the validation-selected state/channel variant."""
import argparse
import json
from pathlib import Path
import random
from analyze_qwerty_corrector import group_values, fscore
from dataset_isolation import sha256
from qwerty_corrector_policy import write_json, policy, summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--comparison-dir", type=Path, required=True)
    p.add_argument("--out", type=Path)
    a = p.parse_args()
    out = a.out or a.comparison_dir / "selected-analysis.json"
    if out.exists():
        raise FileExistsError(out)
    comparison = json.loads((a.comparison_dir/"comparison.json").read_text(encoding="utf-8"))
    if comparison.get("status") != "completed":
        raise ValueError("Comparison not complete")
    model, variant = comparison["validation_selected"].split("/")
    rows = json.loads(Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json").read_text(encoding="utf-8"))["cases"]
    baseline = json.loads((a.comparison_dir/f"test-{model}-none.json").read_text(encoding="utf-8"))["report"]
    selected = json.loads((a.comparison_dir/f"test-{model}-{variant}.json").read_text(encoding="utf-8"))["report"]
    groups = [group_values(rows, r) for r in (baseline, selected)]
    keys = sorted(groups[0])
    rng = random.Random(42)
    deltas = []
    for _ in range(5000):
        sample = rng.choices(keys, k=len(keys))
        scores = [fscore([sum(g[k][i] for k in sample) for i in range(3)]) for g in groups]
        deltas.append(scores[1]-scores[0])
    deltas.sort()
    fresh = json.loads(Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json").read_text(encoding="utf-8"))
    oracle_rows = [{**r, "responses": {c["prompt"]:{"content":r["expected"] if r["expected_action"]=="repair" else r["native_observed"],
        "stopped_eos":True} for c in planned["calls"]}} for r,planned in zip(fresh["cases"],fresh["plan"]["cases"])]
    oracle = policy(oracle_rows)
    oracle["oracle"] = True
    write_json(out.with_name(out.stem+"-fresh-oracle.json"), {"report":oracle, "summary":summary(oracle,fresh["cases"]),
        "scope":"Gold response injection diagnostic only; not model quality or latency; normal responses remain independent"})
    write_json(out, {"selected":comparison["validation_selected"], "comparison_sha256":sha256(a.comparison_dir/"comparison.json"),
        "groups":len(keys), "paired_f0_5_delta_95_percentile_interval":[deltas[125],deltas[4874]],
        "fresh_oracle":summary(oracle,fresh["cases"]),
        "seed":42,"draws":5000,"scope":"Synthetic source-group resampling; same weights before/after filter; not user-frequency accuracy"})
    print(out, deltas[125], deltas[4874])


if __name__ == "__main__":
    main()
