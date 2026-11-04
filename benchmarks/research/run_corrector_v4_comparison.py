"""Compare the fixed validation-selected v4, compact v4 and old corrector on CPU.

Uses a separately built current Windows-policy runner. Never changes installed
IME assets, settings, learning history or model catalogs.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from benchmark_kana_kanji_models import sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-name", default="corrector-v4-nano-20261030-r1")
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--normal", type=Path, default=Path("artifacts/models/zerocon/zerocon-model-standard.gguf"))
    parser.add_argument("--runner", type=Path, default=Path("artifacts/build/corrector-v4-20261030/debug/examples/evaluate_corrector.exe"))
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--extra-models", nargs="*", default=[], help="Additional keys from the same validation report")
    args = parser.parse_args()
    if args.out_dir.exists():
        raise FileExistsError(args.out_dir)
    validation = json.loads(args.validation.read_text(encoding="utf-8"))
    selected = validation["selected"]
    names = dict.fromkeys(("v32-baseline", "q4km", selected, *args.extra_models))
    for key in names:
        spec = validation["models"][key]
        if sha256(spec["model"]) != spec["sha256"]:
            raise ValueError("Model changed since validation")
    args.out_dir.mkdir(parents=True)
    report = {"status": "evaluating", "selected_on_validation": selected,
              "validation_sha256": sha256(args.validation), "normal_sha256": sha256(args.normal),
              "runner_sha256": sha256(args.runner), "results": {}}
    path = args.out_dir / "comparison.json"
    def record():
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    record()
    try:
        for key in names:
            report["results"][key] = {}
            for label, cases in (("300", "data/typo_benchmark_300.jsonl"), ("35", "benchmarks/research/typo_testset.jsonl")):
                out = args.out_dir / f"{key}-{label}.json"
                command = [sys.executable, "scripts/run.py", "benchmarks/research/benchmark_corrector_pipeline.py",
                           "--corrector", validation["models"][key]["model"], "--normal", str(args.normal),
                           "--runner", str(args.runner), "--cases", cases, "--out", str(out),
                           "--backend", "cpu", "--base-port", "8470"]
                print(f"Evaluating {key} {label}", flush=True)
                log = Path("artifacts/logs") / f"{args.run_name}-{key}-{label}-cpu.log"
                with log.open("w", encoding="utf-8") as stream:
                    subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                result = json.loads(out.read_text(encoding="utf-8"))
                report["results"][key][label] = result["summary"]
                print(result["summary"], flush=True)
                record()
        report["status"] = "completed"
    except Exception as error:
        report.update(status="failed", error=str(error))
        raise
    finally:
        record()


if __name__ == "__main__":
    main()
