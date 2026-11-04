"""Evaluate the real Rust correction policy against explicitly selected models.

Owns isolated llama-server processes. Does not start/reconfigure the installed IME.
"""

import argparse
import json
import os
import subprocess
from pathlib import Path

from benchmark_kana_kanji_models import LlamaServerInstance, sha256

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corrector", type=Path, required=True)
    parser.add_argument("--normal", type=Path, required=True)
    parser.add_argument("--cases", type=Path, default=ROOT / "data/typo_benchmark_300.jsonl")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--backend", choices=("cpu", "vulkan", "cuda"), default="cpu")
    parser.add_argument("--base-port", type=int, default=8320)
    parser.add_argument("--runner", type=Path, default=ROOT / "engine/rust/target/debug/examples/evaluate_corrector.exe")
    parser.add_argument("--reading-stage", action="store_true", help="Experimental kana repair then normal-model conversion; includes both requests in timing")
    parser.add_argument("--oracle", action="store_true", help="Diagnostic only: replace model responses by gold surfaces to measure policy reachability")
    args = parser.parse_args()
    if args.oracle and args.reading_stage:
        parser.error("oracle and reading-stage are separate diagnostics")
    if args.out.exists():
        raise FileExistsError(f"Preserving existing results: {args.out}")
    servers = [LlamaServerInstance(str(model.resolve()), args.base_port + i,
                                 0 if args.backend == "cpu" else 99, args.backend)
               for i, model in enumerate((args.corrector, args.normal))]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    try:
        for server in servers:
            server.start()
            for _ in range(3):
                server.complete("\uee00ニホンゴ\uee01")
        result = subprocess.run([str(args.runner.resolve()), str(args.cases.resolve()),
                        str(args.base_port), str(args.base_port + 1),
                        str(ROOT / "ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin"),
                        str(args.out.resolve()), *(["--reading-stage"] if args.reading_stage else ["--oracle"] if args.oracle else [])], capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=ROOT,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if result.returncode:
            raise RuntimeError(f"Rust evaluator exited {result.returncode}:\n{result.stdout}\n{result.stderr}")
    finally:
        for server in servers:
            server.stop()
    report = json.loads(args.out.read_text(encoding="utf-8"))
    report["identity"] = {
        "cases_sha256": sha256(args.cases), "backend": args.backend,
        "reading_stage": args.reading_stage,
        "oracle": args.oracle,
        "corrector": {"path": str(args.corrector), "sha256": sha256(args.corrector)},
        "normal": {"path": str(args.normal), "sha256": sha256(args.normal)},
        "runner_sha256": sha256(args.runner),
        "runtime_sha256": sha256(servers[0].server_exe),
        "reverse_dictionary_sha256": sha256(ROOT / "ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin"),
    }
    cases = report["cases"]
    if args.oracle:
        report["evaluation_scope"] = "Perfect-surface oracle through unchanged correction policy; not model accuracy or latency"
    def accepted(row, output):
        return output is not None and output in [row["expected"], *(row.get("acceptable") or [])]
    # The older 35-case set contains only typos and has no is_clean field.
    typo = [r for r in cases if r["is_clean"] is not True]
    clean = [r for r in cases if r["is_clean"] is True]
    lats = sorted(r["assist_ms"] for r in cases)
    report["summary"] = {
        "total": len(cases), "typo_total": len(typo), "clean_total": len(clean),
        "crashed_cases": sum(r["crashed"] for r in cases),
        "typo_baseline_correct": sum(accepted(r, r["baseline"]) for r in typo),
        "typo_correct_available": sum(accepted(r, r["baseline"]) or accepted(r, r["correction"]) for r in typo),
        "typo_correct_suggestion": sum(accepted(r, r["correction"]) for r in typo),
        "typo_wrong_suggestion": sum(r["correction"] is not None and not accepted(r, r["correction"]) for r in typo),
        "clean_wrong_suggestion": sum(r["correction"] is not None and not accepted(r, r["correction"]) for r in clean),
        "wrong_auto_apply": sum(r["auto_apply"] and not accepted(r, r["correction"]) for r in cases),
        "failed_completion_requests": sum(c["response"] is None for r in cases for c in r["calls"]),
        "p50_ms": lats[len(lats)//2], "p95_ms": lats[min(len(lats)-1, int(len(lats)*.95))],
    }
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
