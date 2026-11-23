"""モデル推論のみ（conversion 840問 × 18モデル）ベンチマーク実行器。

設定画面の「モデル推論」スコープと同一プロトコルで、
`keyboard-prior.exe --benchmark` を 1 モデルにつき 1 プロセス起動し、
読みからモデルが直接生成した表記を top1 として採点する。

- 問題: `benchmarks/common/conversion.jsonl`（840問、入力順）
- 採点: `run_common_candidates.py` の `score_conversion`（Rust と同一規則）を再利用
- プロンプト: `crates/server/src/ai_assist/benchmark.rs` の `Inference::convert` を移植
  （`\\uEE02` 左文脈 + `\\uEE00` カタカナ読み + `\\uEE01`、数字は `nam` へマスクし復元）
- n_predict=80 / timeout=2000ms / CPU（gpu_layers=0）は設定画面と同一
- レイテンシ: worker が返す推論時間 `elapsed_ns`（転送は含まない）

使い方:
  python scripts/run.py benchmarks/common/run_common_model_scope.py --help
  python scripts/run.py benchmarks/common/run_common_model_scope.py --outdir artifacts/results/...

出力（--outdir 直下）:
  summary.json      モデル別集計（全体/部分集合/レイテンシ/壁時間/ハッシュ）
  <model>.jsonl     問題ごとの top1・正誤・推論時間（840行）
  comparison.csv    集計表の CSV
  scatter.png/html  図は run_common_candidates_plot.py が同一 outdir を読んで生成する
"""
import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_common_candidates import (  # noqa: E402
    DICT_DIR,
    LLAMA_DIR,
    MODELS,
    SHORT,
    TRIAL_ROOT,
    load_cases,
    normalize_context,
    score_conversion,
    sha256_file,
    summarize,
)

WORKER = TRIAL_ROOT / "keyboard-prior.exe"
N_PREDICT = 80
TIMEOUT_MS = 2000
PUA_LO, PUA_HI = 0xE000, 0xF8FF


def to_katakana(s: str) -> str:
    return "".join(
        chr(ord(c) + 0x60) if "\u3041" <= c <= "\u3096" else c for c in s
    )


def mask_digits_with_nam(text: str):
    """ASCII/全角数字の連続を `nam` に置換し、元の数字列を返す（Rust と同一）。"""
    masked = []
    digits = []
    current = ""
    for ch in text:
        if "0" <= ch <= "9":
            current += ch
        elif "０" <= ch <= "９":
            current += chr(ord(ch) - 0xFEE0)
        else:
            if current:
                digits.append(current)
                current = ""
                masked.append("nam")
            masked.append(ch)
    if current:
        digits.append(current)
        masked.append("nam")
    return "".join(masked), digits


def restore_nam_digits(surface: str, digits) -> str:
    """`nam` を左から復元する。`name` のような英単語は残す（Rust の2パスを移植）。"""
    if not digits or "nam" not in surface:
        return surface

    def replace_pass(text: str, start: int, fallback: bool):
        out = ""
        rest = text
        idx = start
        while idx < len(digits):
            pos = rest.find("nam")
            if pos < 0:
                break
            out += rest[:pos]
            after = rest[pos + 3:]
            if not fallback:
                nxt = after[0] if after else ""
                keep = ("a" <= nxt <= "w") or ("y" <= nxt <= "z")
            else:
                prev_lower = bool(out) and out[-1].isascii() and out[-1].islower()
                is_english_stem = after.startswith("e") or (
                    after.startswith("ing")
                    and (len(after) == 3 or not (after[3].isascii() and after[3].islower()))
                )
                keep = prev_lower or is_english_stem
            if keep:
                out += "nam"
            else:
                out += digits[idx]
                idx += 1
            rest = after
        out += rest
        return out, idx

    first, consumed = replace_pass(surface, 0, False)
    if consumed < len(digits) and "nam" in first:
        return replace_pass(first, consumed, True)[0]
    return first


def build_prompt(context: str, reading: str):
    target, digits = mask_digits_with_nam(to_katakana(reading))
    masked_context, _ = mask_digits_with_nam(context)
    prompt = f"\uEE02{masked_context}\uEE00{target}\uEE01"
    return prompt, digits


def strip_private_use(text: str) -> str:
    return "".join(ch for ch in text if not (PUA_LO <= ord(ch) <= PUA_HI))


def run_one_model(model_id, display, model_path: Path, cases, gpu_layers: int):
    if not model_path.is_file():
        raise FileNotFoundError(f"model not found: {model_path}")
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join([str(LLAMA_DIR)] + ([env["PATH"]] if env.get("PATH") else []))
    env["AZOOKEY_LLAMA_BACKEND_DIR"] = str(LLAMA_DIR)
    env["AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS"] = str(gpu_layers)
    wall_start = time.perf_counter()
    proc = subprocess.Popen(
        [str(WORKER), "--benchmark"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        cwd=str(TRIAL_ROOT), env=env,
    )
    load_s = None
    results = []
    failures = 0
    try:
        proc.stdin.write(str(model_path.resolve()).encode("utf-8") + b"\n")
        proc.stdin.flush()
        ready = proc.stdout.readline()
        if ready.strip() != b"ready":
            raise RuntimeError(f"worker did not report ready (got {ready[:80]!r})")
        load_s = time.perf_counter() - wall_start
        for case in cases:
            reading = case["target"].replace(" ", "").replace("　", "")
            context = normalize_context(case["left_context"])
            prompt, digits = build_prompt(context, reading)
            payload = prompt.encode("utf-8")
            header = f"{N_PREDICT} {TIMEOUT_MS} {len(payload)}\n".encode("ascii")
            proc.stdin.write(header)
            proc.stdin.write(payload)
            proc.stdin.flush()
            line = proc.stdout.readline()
            if not line:
                raise RuntimeError(f"worker EOF at case {case['id']}")
            reply = json.loads(line.decode("utf-8"))
            reason = reply.get("reason")
            if reason not in ("eos", "stop", "limit"):
                failures += 1
                surface = reading
            else:
                text = strip_private_use(reply["text"]).strip()
                surface = restore_nam_digits(strip_private_use(text).strip(), digits) or reading
            correct, reason_score = score_conversion(
                surface, case["expected"], case.get("acceptable") or [],
                case.get("forbidden_examples") or [],
            )
            results.append({
                "id": case["id"],
                "subset": case["subset"],
                "top1": surface,
                "candidates": [surface],
                "is_correct": correct,
                "reason": reason_score,
                "latency_ms": reply["elapsed_ns"] / 1e6,
                "nbest_ms": 0.0,
            })
        wall_s = time.perf_counter() - wall_start
        return results, wall_s, load_s, failures
    finally:
        try:
            if proc.stdin and not proc.stdin.closed:
                proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=10)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


def write_summary(outdir: Path, summaries, errors, cases, gpu_layers, failures):
    payload = {
        "schema": "zerocon-common-model-scope-840/18models/v1",
        "scope": "model（モデル推論のみ：読みから直接生成、N-best 追加なし）",
        "scope_title": "モデル推論のみベンチマーク（840問）",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "benchmark": "benchmarks/common/conversion.jsonl (840問、入力順)",
        "scoring": "Rust score_conversion 移植（top1 の exact/whitespace/kana/forbidden）",
        "prompt": "\\uEE02<文脈>\\uEE00<カタカナ読み>\\uEE01, n_predict=80, timeout=2000ms, 数字は nam マスク",
        "condition": {
            "gpu_layers": gpu_layers, "backend": "cpu (same-condition)",
            "worker": str(WORKER), "runtime": str(LLAMA_DIR), "dictionary": str(DICT_DIR),
            "worker_bytes": WORKER.stat().st_size,
            "cases": len(cases), "case_ids": [cases[0]["id"], cases[-1]["id"]] if cases else [],
        },
        "models": summaries,
        "failures": failures,
        "errors": errors,
    }
    with open(outdir / "summary.json", "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_csv(outdir: Path, summaries):
    import csv

    with open(outdir / "comparison.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["model_id", "display_name", "count", "correct", "accuracy_pct",
                    "wall_s", "load_s", "mean_latency_ms", "p50_ms", "p95_ms", "p99_ms",
                    "sum_elapsed_s", "model_mb", "model_sha256"])
        for s in summaries:
            w.writerow([s["model_id"], s["display_name"], s.get("count", 0), s.get("correct", 0),
                        round(s.get("accuracy", 0), 2),
                        (round(s["wall_s"], 2) if s.get("wall_s", 0) > 0 else ""),
                        (round(s["load_s"], 2) if s.get("load_s", 0) > 0 else ""),
                        round(s.get("mean_latency_ms", 0), 2),
                        round(s.get("p50_ms", 0), 2), round(s.get("p95_ms", 0), 2), round(s.get("p99_ms", 0), 2),
                        round(s.get("sum_elapsed_s", 0), 2),
                        round(s.get("model_bytes", 0) / 1024 / 1024, 1), s.get("model_sha256", "")])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--outdir", type=Path,
                    default=ROOT / "artifacts/results/common-model-scope-840-18models-20261123")
    ap.add_argument("--gpu-layers", type=int, default=0, help="CPU 固定の既定 0")
    ap.add_argument("--limit", type=int, default=0, help="先頭 N 問だけ（スモーク用）")
    ap.add_argument("--models", nargs="*", default=[], help="model_id の部分一致で絞る")
    ap.add_argument("--overwrite", action="store_true", help="既存のモデル結果を上書き")
    args = ap.parse_args()

    if not WORKER.is_file():
        print(f"missing keyboard-prior.exe: {WORKER}", file=sys.stderr)
        return 1
    if not (LLAMA_DIR / "llama.dll").is_file():
        print(f"missing llama.dll: {LLAMA_DIR}", file=sys.stderr)
        return 1

    cases = load_cases()
    if args.limit:
        cases = cases[: args.limit]
    print(f"cases: {len(cases)} (ids {cases[0]['id']}..{cases[-1]['id']})", flush=True)

    models = [m for m in MODELS if not args.models or any(s in m[0] for s in args.models)]
    print(f"models: {len(models)}", flush=True)
    args.outdir.mkdir(parents=True, exist_ok=True)

    summaries = []
    errors = []
    failures = {}
    for model_id, display, model_path in models:
        out_jsonl = args.outdir / f"{model_id}.jsonl"
        if out_jsonl.is_file() and not args.overwrite:
            try:
                rows = [json.loads(l) for l in out_jsonl.read_text(encoding="utf-8").splitlines() if l.strip()]
                if len(rows) == len(cases):
                    s = summarize(model_id, display, model_path, rows, -1, -1)
                    summaries.append(s)
                    print(f"[skip] {model_id}: {s['correct']}/{s['count']} acc={s['accuracy']:.1f}%", flush=True)
                    continue
            except Exception as e:
                print(f"[warn] existing {out_jsonl.name} unreadable ({e}); re-run", flush=True)
        print(f"[run] {model_id} ({display}) ...", flush=True)
        t0 = time.perf_counter()
        try:
            results, wall_s, load_s, failed = run_one_model(
                model_id, display, model_path, cases, args.gpu_layers)
            s = summarize(model_id, display, model_path, results, wall_s, load_s)
            s["failed_inference"] = failed
            failures[model_id] = failed
            with open(out_jsonl, "w", encoding="utf-8") as f:
                for r in results:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            elapsed = time.perf_counter() - t0
            print(f"[done] {model_id}: {s['correct']}/{s['count']} acc={s['accuracy']:.1f}% "
                  f"mean={s['mean_latency_ms']:.1f}ms p95={s['p95_ms']:.1f}ms "
                  f"wall={wall_s:.1f}s load={load_s:.1f}s (host {elapsed:.1f}s)", flush=True)
            summaries.append(s)
        except Exception as e:
            print(f"[error] {model_id}: {e}", file=sys.stderr, flush=True)
            errors.append({"model_id": model_id, "error": str(e)})
            summaries.append({"model_id": model_id, "display_name": display,
                              "short": SHORT.get(model_id, model_id), "model_path": str(model_path),
                              "count": 0, "correct": 0, "accuracy": 0.0, "error": str(e)})
        write_summary(args.outdir, summaries, errors, cases, args.gpu_layers, failures)

    write_summary(args.outdir, summaries, errors, cases, args.gpu_layers, failures)
    write_csv(args.outdir, summaries)
    print(f"summary: {args.outdir / 'summary.json'}", flush=True)
    if errors:
        print(f"errors: {len(errors)}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
