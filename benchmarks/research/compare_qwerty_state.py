"""Frozen 2x3 comparison: kana/raw-state model, no/state/key-channel veto.

Q4_K_M is fixed before evaluation. No test-driven parameter selection.
"""
import argparse
import json
from pathlib import Path

from dataset_isolation import load_jsonl, sha256
from qwerty_channel_filter import ChannelFilter
from qwerty_corrector_policy import evaluate_gguf, write_json
from select_qwerty_corrector import eligible


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    a.out.mkdir(parents=True)
    fixtures = {s: json.loads(Path(f"artifacts/results/corrector-qwerty-state-20261030-r1-{s}-fixture.json").read_text(encoding="utf-8")) for s in ("val", "test")}
    baseline = json.loads(Path("artifacts/results/corrector-qwerty-state-20261030-r1-val-baseline.json").read_text(encoding="utf-8"))
    legacy = json.loads(Path("artifacts/results/corrector-qwerty-v4-20261030-wise-r2-test/legacy-qwerty-labelled-previous-v4.json").read_text(encoding="utf-8"))
    old_rows = [{**r, "expected_action": "keep" if r["is_clean"] else "repair"} for r in load_jsonl("data/typo_benchmark_300.jsonl") if r["is_clean"] or r["typo_type"] in ("adjacent", "missing_vowel", "transposition")]
    normal = {r["id"]:r["normal_response"] for r in legacy["cases"]}
    for row in old_rows:
        row["normal_response"] = normal[row["id"]]
    fixtures["legacy"] = {"cases": old_rows, "plan": {"cases": legacy["cases"]}}
    fixtures["fresh"] = json.loads(Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json").read_text(encoding="utf-8"))
    specs = {"previous-v4": ("artifacts/models/zerocon-corrector-v4-iq4nl-emb8.gguf", "kana"),
        "previous-qwerty": ("artifacts/models/zerocon-corrector-qwerty-v4-q4km.gguf", "kana"),
        "kana": ("artifacts/models/corrector-qwerty-state-kana-20261030-r1-q4km.gguf", "kana"),
        "raw-state": ("artifacts/models/corrector-qwerty-state-raw-20261030-r1-q4km.gguf", "raw-state")}
    plan = {"specs": {k:{"path":v[0], "contract":v[1], "sha256":sha256(v[0])} for k,v in specs.items()},
        "script_sha256": sha256(__file__), "filters": ["none", "state-only", "state-channel"],
        "sources": {p:sha256(p) for p in ["benchmarks/research/qwerty_channel_filter.py", "training/qwerty_input_contract.py",
            "artifacts/results/corrector-qwerty-state-20261030-r1-val-fixture.json", "artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json",
            "artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json", "data/typo_benchmark_300.jsonl"]},
        "policy": "Predeclared Q4, one-key channel, backspace veto; no test-tuned settings", "results": {}}
    write_json(a.out / "plan.json", plan)
    channel = ChannelFilter()
    # Finish validation for every variant before opening test generations.
    for split, fixture in fixtures.items():
        plan["results"][split] = {}
        for name, (path, contract) in specs.items():
            result = evaluate_gguf(fixture, path, port=8570, input_contract=contract)
            write_json(a.out / f"{split}-{name}-none.json", result)
            variants = {"none": result}
            for key, enabled in (("state-only", False), ("state-channel", True)):
                filtered = channel.apply(result, fixture["cases"], channel=enabled)
                write_json(a.out / f"{split}-{name}-{key}.json", filtered)
                variants[key] = filtered
            for key, variant in variants.items():
                values = {"summary": variant["summary"]}
                if split == "val":
                    values["eligible"] = eligible(variant["summary"], baseline["summary"])
                plan["results"][split][name+"/"+key] = values
            print(split, name, {k:v["summary"] for k,v in variants.items()}, flush=True)
            write_json(a.out / "comparison.json", plan)
        if split == "val":
            allowed = [k for k,v in plan["results"][split].items() if v["eligible"]]
            plan["validation_selected"] = max(allowed, key=lambda k: plan["results"][split][k]["summary"]["case_f0_5"]) if allowed else None
            write_json(a.out / "validation-selection.json", {"selected":plan["validation_selected"], "validation":plan["results"][split]})
    plan["status"] = "completed"
    write_json(a.out / "comparison.json", plan)


if __name__ == "__main__":
    main()
