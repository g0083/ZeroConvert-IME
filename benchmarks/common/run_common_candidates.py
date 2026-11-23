"""共通候補生成（conversion 840問 × 10モデル）ベンチマーク実行器。

Windows 設定画面の「共通候補生成」と同一プロトコルで、
`azookey-server.exe --conversion-benchmark` を 1 モデルにつき 1 プロセス
（モデルを読み込んで連続処理、逐次・入力順）で実行する。

- 問題: `benchmarks/common/conversion.jsonl`（840問、入力順）
- 採点: Rust `win/crates/server/src/ai_assist/benchmark.rs` の
  `score_conversion` / `run_candidate_batch` と同一規則の Python 移植
- 候補生成: Swift `ConversionBenchmark.run`
  （Zenzai + 標準辞書 N-best 追加 + 順位付け、UTF-8 JSON で受信）
- 実行時間: ドライバ壁時計（起動〜840問完了）＋ Swift 計測 `elapsed_ns` 合計
- 条件: CPU（gpu_layers=0）同条件。辞書・ランタイム・サーバは
  `ZeroConvertIME-windows/` の試験環境を使う（辞書・学習は無効、設定画面と同一）

使い方:
  python scripts/run.py benchmarks/common/run_common_candidates.py --help
  python scripts/run.py benchmarks/common/run_common_candidates.py --limit 5
  python scripts/run.py benchmarks/common/run_common_candidates.py --outdir artifacts/results/...

出力（--outdir 直下）:
  summary.json          10モデル集計（全体/部分集合/レイテンシ/壁時間/ハッシュ）
  <model>.jsonl         問題ごとの候補・top1・正誤・レイテンシ（840行）
  comparison.csv        集計表の CSV
  scatter.py の図は run_common_candidates_plot.py で生成（同一 outdir を読む）
"""
import argparse
import csv
import hashlib
import json
import os
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONVERSION_JSONL = ROOT / "benchmarks/common/conversion.jsonl"
PARITY_JSONL = ROOT / "benchmarks/common/parity_cases.jsonl"
TRIAL_ROOT = ROOT / "ZeroConvertIME-windows"
SERVER_EXE = TRIAL_ROOT / "azookey-server.exe"
LLAMA_DIR = TRIAL_ROOT / "llama"
DICT_DIR = TRIAL_ROOT / "Dictionary"

# ユーザ指定 10 種（表示名 → 実ファイル）。
# 「iq4em8」は現存ファミリ名に存在しないため、既存の IQ4_NL/emb8 として解釈する。
# 2026-11-23 追加: v3/v4 も同条件で追加した（合計18種）。
# - v3 Standard は IQ4_NL emb8 / Q8_0 が存在する。
# - v4 Nano は IQ4_NL emb8 / Q8_0、Standard v4 は standard-v4-nano-transfer の同量子化。
# - v3 Nano には Q8_0 リリースが無いため、高精度側は Q6_K を用いた（報告書に記載）。
MODELS = [
    ("zerocon-standard-v4.2-iq4nl-emb8", "ZeroCon Model Standard v4.2 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon/zerocon-standard-v4.2-iq4nl-emb8.gguf"),
    ("zerocon-standard-v4.2-q8_0", "ZeroCon Model Standard v4.2 Q8_0",
     ROOT / "artifacts/models/zerocon/zerocon-standard-v4.2-q8_0.gguf"),
    ("zerocon-standard-v4.1-iq4nl-emb8", "ZeroCon Model Standard v4.1 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon/zerocon-standard-v4.1-iq4nl-emb8.gguf"),
    ("zerocon-standard-v4.1-q8_0", "ZeroCon Model Standard v4.1 Q8_0",
     ROOT / "artifacts/models/zerocon/zerocon-standard-v4.1-q8_0.gguf"),
    ("zerocon-nano-v4.2-iq4nl-emb8", "ZeroCon Model Nano v4.2 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4.2-iq4nl-emb8.gguf"),
    ("zerocon-nano-v4.2-q8_0", "ZeroCon Model Nano v4.2 Q8_0",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4.2-q8_0.gguf"),
    ("zerocon-nano-v4.1-iq4nl-emb8", "ZeroCon Model Nano v4.1 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4.1-iq4nl-emb8.gguf"),
    ("zerocon-nano-v4.1-q8_0", "ZeroCon Model Nano v4.1 Q8_0",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4.1-q8_0.gguf"),
    ("zenz-v3.2-small-q4km", "Zenz v3.2 small Q4_K_M",
     ROOT / "artifacts/models/zenz-v3.2-small-q4km.gguf"),
    ("zenz-v3.2-xsmall-q4km", "Zenz v3.2 xsmall Q4_K_M",
     ROOT / "artifacts/models/zenz-v3.2-xsmall-q4km.gguf"),
    ("zerocon-standard-v3-iq4nl-emb8", "ZeroCon Model Standard v3 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon-standard-v3-iq4nl-emb8.gguf"),
    ("zerocon-standard-v3-q8_0", "ZeroCon Model Standard v3 Q8_0",
     ROOT / "artifacts/models/zerocon-standard-v3-q8_0.gguf"),
    ("zerocon-nano-v3-iq4nl-emb8", "ZeroCon Model Nano v3 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon-nano-v3-iq4nl-emb8.gguf"),
    ("zerocon-nano-v3-q6k", "ZeroCon Model Nano v3 Q6_K",
     ROOT / "artifacts/models/zerocon-nano-v3-q6k.gguf"),
    ("zerocon-nano-v4-iq4nl-emb8", "ZeroCon Model Nano v4 IQ4_NL emb8",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4-iq4nl-emb8.gguf"),
    ("zerocon-nano-v4-q8_0", "ZeroCon Model Nano v4 Q8_0",
     ROOT / "artifacts/models/zerocon/zerocon-nano-v4-q8_0.gguf"),
    ("standard-v4-iq4nl-emb8", "ZeroCon Model Standard v4 IQ4_NL emb8",
     ROOT / "artifacts/models/standard-v4-nano-transfer-iq4nl-emb8.gguf"),
    ("standard-v4-q8_0", "ZeroCon Model Standard v4 Q8_0",
     ROOT / "artifacts/models/standard-v4-nano-transfer-q8_0.gguf"),
]
SHORT = {
    "zerocon-standard-v4.2-iq4nl-emb8": "Std v4.2 IQ4",
    "zerocon-standard-v4.2-q8_0": "Std v4.2 Q8",
    "zerocon-standard-v4.1-iq4nl-emb8": "Std v4.1 IQ4",
    "zerocon-standard-v4.1-q8_0": "Std v4.1 Q8",
    "zerocon-nano-v4.2-iq4nl-emb8": "Nano v4.2 IQ4",
    "zerocon-nano-v4.2-q8_0": "Nano v4.2 Q8",
    "zerocon-nano-v4.1-iq4nl-emb8": "Nano v4.1 IQ4",
    "zerocon-nano-v4.1-q8_0": "Nano v4.1 Q8",
    "zenz-v3.2-small-q4km": "Zenz small Q4",
    "zenz-v3.2-xsmall-q4km": "Zenz xsmall Q4",
    "zerocon-standard-v3-iq4nl-emb8": "Std v3 IQ4",
    "zerocon-standard-v3-q8_0": "Std v3 Q8",
    "zerocon-nano-v3-iq4nl-emb8": "Nano v3 IQ4",
    "zerocon-nano-v3-q6k": "Nano v3 Q6",
    "zerocon-nano-v4-iq4nl-emb8": "Nano v4 IQ4",
    "zerocon-nano-v4-q8_0": "Nano v4 Q8",
    "standard-v4-iq4nl-emb8": "Std v4 IQ4",
    "standard-v4-q8_0": "Std v4 Q8",
}


# --- 採点（benchmark.rs score_conversion の移植）---
def clean_scoring_input(s: str) -> str:
    return s.strip().replace("\r", "").replace("\n", "")


def normalize_whitespace(s: str) -> str:
    # Rust: is_whitespace() + U+3000 ; Python isspace は概ね一致、U+3000 を明示除去する。
    return "".join(ch for ch in s if not ch.isspace() and ch != "　")


def katakana_to_hiragana(s: str) -> str:
    out = []
    for ch in s:
        if "\u30a1" <= ch <= "\u30f6":
            out.append(chr(ord(ch) - 0x60))
        else:
            out.append(ch)
    return "".join(out)


def score_conversion(output: str, expected: str, acceptable, forbidden):
    out = clean_scoring_input(output)
    exp = expected.strip()
    for fb in forbidden:
        fb_trim = fb.strip()
        if fb_trim and fb_trim in out:
            return False, "forbidden"
    if out == exp or any(a.strip() == out for a in acceptable):
        return True, "exact"
    if normalize_whitespace(out) == normalize_whitespace(exp):
        return True, "whitespace"
    hira_out = katakana_to_hiragana(out)
    if hira_out == katakana_to_hiragana(exp):
        return True, "kana"
    for acc in acceptable:
        acc_trim = acc.strip()
        if normalize_whitespace(out) == normalize_whitespace(acc_trim):
            return True, "whitespace_acceptable"
        if hira_out == katakana_to_hiragana(acc_trim):
            return True, "kana_acceptable"
    return False, "mismatch"


def normalize_context(context: str) -> str:
    t = context.strip()
    return "" if t == "なし" else t


def percentile_lat(latencies, p: float) -> float:
    if not latencies:
        return 0.0
    s = sorted(latencies)
    rank = int(__import__("math").ceil(p / 100.0 * len(s)))
    return s[min(max(rank - 1, 0), len(s) - 1)]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_cases():
    cases = []
    with open(CONVERSION_JSONL, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    cases.sort(key=lambda c: c["id"])
    return cases


# --- ワーカー通信（benchmark_worker.rs と同一手順）---
def read_benchmark_line(proc: subprocess.Popen):
    """`benchmark:` 始まりの行のペイロード（bytes）を返す（診断行は読み飛ばす）。"""
    skipped = 0
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError(f"worker EOF after {skipped} ignored lines (returncode={proc.poll()})")
        if line.startswith(b"benchmark:"):
            return line[len(b"benchmark:"):]
        skipped += 1
        if skipped > 10000:
            raise RuntimeError("too many non-benchmark lines from worker")


def run_one_model(model_id, display, model_path: Path, cases, gpu_layers: int):
    if not model_path.is_file():
        raise FileNotFoundError(f"model not found: {model_path}")
    env = dict(os.environ)
    # Tauri Worker::start と同一の PATH / 環境を再現する。
    path_parts = [str(LLAMA_DIR)] + ([env["PATH"]] if env.get("PATH") else [])
    env["PATH"] = os.pathsep.join(path_parts)
    env["AZOOKEY_LLAMA_BACKEND_DIR"] = str(LLAMA_DIR)
    env["AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS"] = str(gpu_layers)
    wall_start = time.perf_counter()
    proc = subprocess.Popen(
        [str(SERVER_EXE), "--conversion-benchmark"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        cwd=str(TRIAL_ROOT), env=env,
    )
    load_s = None
    results = []
    try:
        def send_line(text: str):
            proc.stdin.write(text.encode("utf-8") + b"\n")
            proc.stdin.flush()

        send_line(str(model_path.resolve()))
        send_line(str(DICT_DIR.resolve()))
        send_line(str(gpu_layers))
        if read_benchmark_line(proc).strip() != b"ready":
            raise RuntimeError("worker did not return benchmark:ready")
        load_s = time.perf_counter() - wall_start
        for case in cases:
            reading = case["target"].replace(" ", "").replace("　", "")
            context = normalize_context(case["left_context"])
            payload = json.dumps([reading, context], ensure_ascii=False)
            proc.stdin.write(payload.encode("utf-8") + b"\n")
            proc.stdin.flush()
            raw = read_benchmark_line(proc)
            comp = json.loads(raw.decode("utf-8"))
            candidates = comp.get("candidates") or []
            top1 = candidates[0] if candidates else ""
            correct, reason = score_conversion(
                top1, case["expected"], case.get("acceptable") or [], case.get("forbidden_examples") or [])
            results.append({
                "id": case["id"],
                "subset": case["subset"],
                "top1": top1,
                "candidates": candidates,
                "is_correct": correct,
                "reason": reason,
                "latency_ms": comp["elapsed_ns"] / 1e6,
                "nbest_ms": comp["nbest_ns"] / 1e6,
            })
        wall_s = time.perf_counter() - wall_start
        return results, wall_s, load_s
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


def summarize(model_id, display, model_path, results, wall_s, load_s):
    lat = [r["latency_ms"] for r in results]
    nbest = [r["nbest_ms"] for r in results]
    correct = sum(1 for r in results if r["is_correct"])
    n = len(results)
    by_subset = {}
    subsets = []
    for r in results:
        if r["subset"] not in by_subset:
            by_subset[r["subset"]] = [0, 0]
            subsets.append(r["subset"])
        by_subset[r["subset"]][1] += 1
        if r["is_correct"]:
            by_subset[r["subset"]][0] += 1
    by_subset = {k: {"correct": v[0], "count": v[1], "accuracy": (v[0] / v[1] * 100 if v[1] else 0)}
                 for k, v in by_subset.items()}
    return {
        "model_id": model_id,
        "display_name": display,
        "short": SHORT.get(model_id, model_id),
        "model_path": str(model_path),
        "model_bytes": model_path.stat().st_size,
        "model_sha256": sha256_file(model_path),
        "count": n,
        "correct": correct,
        "accuracy": (correct / n * 100 if n else 0.0),
        "mean_latency_ms": (sum(lat) / n if n else 0.0),
        "p50_ms": percentile_lat(lat, 50), "p95_ms": percentile_lat(lat, 95), "p99_ms": percentile_lat(lat, 99),
        "mean_nbest_ms": (sum(nbest) / n if n else 0.0),
        "sum_elapsed_s": sum(lat) / 1000.0,
        "wall_s": wall_s,
        "load_s": load_s,
        "by_subset": by_subset,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--outdir", type=Path,
                    default=ROOT / "artifacts/results/common-candidates-840-18models-20261123")
    ap.add_argument("--gpu-layers", type=int, default=0, help="CPU 固定の既定 0")
    ap.add_argument("--limit", type=int, default=0, help="先頭 N 問だけ（スモーク用）")
    ap.add_argument("--models", nargs="*", default=[], help="model_id の部分一致で絞る")
    ap.add_argument("--overwrite", action="store_true", help="既存のモデル結果を上書き")
    args = ap.parse_args()

    for p, name in [(SERVER_EXE, "azookey-server.exe"), (DICT_DIR / "louds/charID.chid", "辞書")]:
        if not p.exists():
            print(f"missing {name}: {p}", file=sys.stderr)
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
    for model_id, display, model_path in models:
        out_jsonl = args.outdir / f"{model_id}.jsonl"
        if out_jsonl.is_file() and not args.overwrite:
            # 既存結果の集計を再利用（安全のため件数一致時のみ）
            try:
                rows = [json.loads(l) for l in out_jsonl.read_text(encoding="utf-8").splitlines() if l.strip()]
                if len(rows) == len(cases):
                    s = summarize(model_id, display, model_path, rows, -1, -1)
                    # wall/load は集計ファイルから復元する（なければ summary.json の値を使う）
                    summaries.append(s)
                    print(f"[skip] {model_id}: {s['correct']}/{s['count']}  acc={s['accuracy']:.1f}%", flush=True)
                    continue
            except Exception as e:
                print(f"[warn] existing {out_jsonl.name} unreadable ({e}); re-run", flush=True)
        print(f"[run] {model_id} ({display}) ...", flush=True)
        t0 = time.perf_counter()
        try:
            results, wall_s, load_s = run_one_model(model_id, display, model_path, cases, args.gpu_layers)
            s = summarize(model_id, display, model_path, results, wall_s, load_s)
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
            summaries.append({"model_id": model_id, "display_name": display, "short": SHORT.get(model_id, model_id),
                              "model_path": str(model_path), "count": 0, "correct": 0, "accuracy": 0.0,
                              "error": str(e)})
        # 途中結果をこまめに保存する
        write_summary(args.outdir, summaries, errors, cases, args.gpu_layers)

    write_summary(args.outdir, summaries, errors, cases, args.gpu_layers)
    write_csv(args.outdir, summaries)
    print(f"summary: {args.outdir / 'summary.json'}", flush=True)
    print(f"csv: {args.outdir / 'comparison.csv'}", flush=True)
    if errors:
        print(f"errors: {len(errors)}", file=sys.stderr)
        return 2
    return 0


def write_summary(outdir: Path, summaries, errors, cases, gpu_layers):
    try:
        # reuse wall/load from jsonl? keep what we have; for skipped reads fill from previous summary.json
        prev = {}
        prev_path = outdir / "summary.json"
        if prev_path.is_file():
            for m in json.loads(prev_path.read_text(encoding="utf-8")).get("models", []):
                prev[m["model_id"]] = m
    except Exception:
        prev = {}
    for s in summaries:
        if s.get("wall_s", -1) == -1 and s["model_id"] in prev:
            s["wall_s"] = prev[s["model_id"]].get("wall_s", -1)
            s["load_s"] = prev[s["model_id"]].get("load_s", -1)
            for k in ("mean_latency_ms", "p50_ms", "p95_ms", "p99_ms", "mean_nbest_ms",
                      "sum_elapsed_s", "model_bytes", "model_sha256", "by_subset", "count", "correct", "accuracy"):
                if k in prev[s["model_id"]]:
                    s[k] = prev[s["model_id"]][k]
    # 部分実行（--models で絞った場合）でも、今回測っていないモデルの集計を残す。
    seen = {s["model_id"] for s in summaries}
    carried = [m for mid, m in prev.items() if mid not in seen]
    summaries = summaries + carried
    payload = {
        "schema": "zerocon-common-candidates-840/18models/v1",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "benchmark": "benchmarks/common/conversion.jsonl (840問、入力順)",
        "scope": "candidates（共通候補生成：Zenzai + N-best 追加 + 順位付け）",
        "scoring": "Rust score_conversion 移植（top1 の exact/whitespace/kana/forbidden）",
        "condition": {
            "gpu_layers": gpu_layers,
            "backend": ("vulkan (full-layer offload)" if gpu_layers > 0 else "cpu (same-condition)"),
            "server": str(SERVER_EXE), "runtime": str(LLAMA_DIR), "dictionary": str(DICT_DIR),
            "server_bytes": SERVER_EXE.stat().st_size,
            "cases": len(cases), "case_ids": [cases[0]["id"], cases[-1]["id"]] if cases else [],
        },
        "scope_title": ("共通候補生成ベンチマーク（840問・Vulkan）" if gpu_layers > 0
                        else "共通候補生成ベンチマーク（840問）"),
        "models": summaries,
        "errors": errors,
    }
    with open(outdir / "summary.json", "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_csv(outdir: Path, summaries):
    with open(outdir / "comparison.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["model_id", "display_name", "count", "correct", "accuracy_pct",
                    "wall_s", "load_s", "mean_latency_ms", "p50_ms", "p95_ms", "p99_ms",
                    "mean_nbest_ms", "sum_elapsed_s", "model_mb", "model_sha256"])
        for s in summaries:
            w.writerow([s["model_id"], s["display_name"], s.get("count", 0), s.get("correct", 0),
                        round(s.get("accuracy", 0), 2),
                        (round(s["wall_s"], 2) if s.get("wall_s") and s["wall_s"] > 0 else ""),
                        (round(s["load_s"], 2) if s.get("load_s") and s["load_s"] > 0 else ""),
                        round(s.get("mean_latency_ms", 0), 2),
                        round(s.get("p50_ms", 0), 2), round(s.get("p95_ms", 0), 2), round(s.get("p99_ms", 0), 2),
                        round(s.get("mean_nbest_ms", 0), 2), round(s.get("sum_elapsed_s", 0), 2),
                        round(s.get("model_bytes", 0) / 1024 / 1024, 1), s.get("model_sha256", "")])


if __name__ == "__main__":
    raise SystemExit(main())
