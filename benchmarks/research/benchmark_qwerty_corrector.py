"""Frozen QWERTY test with live model HTTP and identical native correction policy."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from dataset_isolation import load_jsonl, sha256
from qwerty_corrector_policy import policy, summary, write_json, RUNNER


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--selection", type=Path, required=True)
    p.add_argument("--test-fixture", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    a = p.parse_args()
    if a.out_dir.exists():
        raise FileExistsError(a.out_dir)
    selection = json.loads(a.selection.read_text(encoding="utf-8"))
    key = selection["selected"]
    if not key:
        raise ValueError("No validation-eligible model")
    chosen = selection["models"][key]["model"]
    if sha256(chosen["path"]) != chosen["sha256"]:
        raise ValueError("Selected model changed")
    fixture = json.loads(a.test_fixture.read_text(encoding="utf-8"))
    if fixture["split"] != "test":
        raise ValueError("Expected held-out test fixture")
    a.out_dir.mkdir(parents=True)
    cases = {"native-test": fixture["cases"],
             "legacy-qwerty-labelled": [{**r, "expected_action": "keep" if r["is_clean"] else "repair"}
                 for r in load_jsonl("data/typo_benchmark_300.jsonl")
                 if r["is_clean"] or r["typo_type"] in ("adjacent", "missing_vowel", "transposition")]}
    paths = {}
    for label, rows in cases.items():
        path = a.out_dir / f"{label}-cases.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False)+"\n" for r in rows), encoding="utf-8")
        paths[label] = path
    models = {"previous-v4": Path("artifacts/models/zerocon-corrector-v4-iq4nl-emb8.gguf"), "qwerty-v4": Path(chosen["path"])}
    comparison = {"status": "evaluating", "selection_sha256": sha256(a.selection),
        "test_fixture_sha256": sha256(a.test_fixture), "selected": chosen, "results": {},
        "scope": "Actual correction model HTTP + native policy; not TSF/Swift dictionary candidates/UI; legacy subset has unverified type labels"}
    output = a.out_dir / "comparison.json"
    write_json(output, comparison)
    for label, path in paths.items():
        comparison["results"][label] = {}
        for name, model in models.items():
            target = a.out_dir / f"{label}-{name}.json"
            command = [sys.executable, "scripts/run.py", "benchmarks/research/benchmark_corrector_pipeline.py",
                "--cases", str(path), "--corrector", str(model), "--normal", "artifacts/models/zerocon/zerocon-model-standard.gguf",
                "--runner", str(RUNNER), "--base-port", "8550", "--out", str(target)]
            print(label, name, flush=True)
            log = Path("artifacts/logs") / f"corrector-qwerty-20261030-{label}-{name}.log"
            with log.open("w", encoding="utf-8") as stream:
                subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            report = json.loads(target.read_text(encoding="utf-8"))
            result = summary(report, cases[label])
            result.update(p50_ms=report["summary"]["p50_ms"], p95_ms=report["summary"]["p95_ms"])
            comparison["results"][label][name] = result
            # Verify the replay used for training makes the same decisions as
            # a live model run. Only actual responses from this run are replayed.
            replay_rows = [{**row, "normal_response": measured["normal_response"],
                "responses": {c["prompt"]: c["response"] for c in measured["calls"]}}
                for row, measured in zip(cases[label], report["cases"])]
            repeated = policy(replay_rows)
            if [(r["correction"], r["auto_apply"]) for r in repeated["cases"]] != [(r["correction"], r["auto_apply"]) for r in report["cases"]]:
                raise ValueError("Native replay decisions differed from live model requests")
            print(result, flush=True)
            write_json(output, comparison)
    comparison["status"] = "completed"
    comparison["replay_live_decisions_identical"] = True
    write_json(output, comparison)
