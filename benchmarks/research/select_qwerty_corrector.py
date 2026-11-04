"""Choose a QWERTY quantization using native-policy validation only."""
import argparse
import json
from pathlib import Path

from dataset_isolation import sha256
from qwerty_corrector_policy import evaluate_gguf, write_json


def eligible(summary, baseline):
    return not summary.get("crashed", 0) and all(summary.get(k, 0) <= baseline.get(k, 0)
        for k in ("keep_proposed", "wait_proposed", "wrong_auto_applied", "preservation_proposed"))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--input-contract", choices=("kana", "raw-state"), default="kana")
    a = p.parse_args()
    if a.out_dir.exists():
        raise FileExistsError(a.out_dir)
    fixture = json.loads(a.fixture.read_text(encoding="utf-8"))
    base = json.loads(a.baseline.read_text(encoding="utf-8"))
    if base["fixture_sha256"] != sha256(a.fixture):
        raise ValueError("Baseline fixture mismatch")
    a.out_dir.mkdir(parents=True)
    results = {}
    for quant in ("f16", "q4km", "q6k", "q8_0", "iq4nl-emb8"):
        model = Path("artifacts/models") / f"{a.run_name}-{quant}.gguf"
        report = evaluate_gguf(fixture, model, input_contract=a.input_contract)
        report["fixture_sha256"] = sha256(a.fixture)
        report["model"]["bytes"] = model.stat().st_size
        write_json(a.out_dir / f"{quant}.json", report)
        results[quant] = {"model": report["model"], "summary": report["summary"],
                          "eligible": eligible(report["summary"], base["summary"])}
        print(quant, results[quant], flush=True)
    candidates = [k for k in results if k != "f16" and results[k]["eligible"]]
    selected = max(candidates, key=lambda k: (results[k]["summary"]["case_f0_5"], -results[k]["model"]["bytes"])) if candidates else None
    write_json(a.out_dir / "selection.json", {"selected": selected, "models": results,
        "baseline": base["summary"], "fixture_sha256": sha256(a.fixture),
        "policy": "Max native policy case F0.5, normal/wait/wrong-auto no worse than baseline, size breaks ties; no test cases used"})
    print("Selected", selected, flush=True)
