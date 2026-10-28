# -*- coding: utf-8 -*-
"""
かな漢字変換ベンチマーク (共通ランナー)

`data/kana_kanji_llm_testset_5x.jsonl` (全230問) を任意のGGUFモデル群で評価する。
モデルごとの正答数・カテゴリ別成績・誤変換を JSON に保存し、Standard との差分を比較できる。

使用例:
    python scripts/run.py benchmarks/research/benchmark_kana_kanji_models.py \
        --models "Standard=ZeroConvertIME-windows/zenz.gguf" \
                  "LoRA Q4_K_M=artifacts/models/zerocon-model-small-lora-q4km.gguf"
"""

import argparse
import hashlib
import socket
import statistics
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
TESTSET = os.path.join(ROOT, 'data', 'kana_kanji_llm_testset_5x.jsonl')
DEFAULT_OUT = os.path.join(ROOT, 'artifacts', 'results', 'benchmark_kana_kanji_models.json')


def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])


def evaluate_output(output: str, case: dict) -> tuple:
    """benchmarks/research/benchmark_nano_v2.py と同じ厳格判定。"""
    out = output.strip().replace("\r", "").replace("\n", "")
    expected = case["expected"].strip()
    acceptable = [a.strip() for a in case.get("acceptable", [])]
    forbidden = [f.strip() for f in case.get("forbidden_examples", [])]

    for fb in forbidden:
        if fb and fb in out:
            return 0, f"NG (forbidden: {fb})"

    if out == expected or out in acceptable:
        return 2, "OK (Perfect)"

    if out.replace(" ", "").replace("\u3000", "") == expected.replace(" ", "").replace("\u3000", ""):
        return 2, "OK (Whitespace variant)"

    hira_out = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in out])
    hira_exp = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in expected])
    if hira_out == hira_exp:
        return 2, "OK (Kana variant)"

    return 0, f"NG (out=[{out}] vs exp=[{expected}])"




class LlamaServerInstance:
    def __init__(self, model_path: str, port: int, n_gpu_layers: int = 99, backend: str = "vulkan"):
        self.model_path = model_path
        self.port = port
        self.n_gpu_layers = n_gpu_layers
        self.proc = None
        self.server_exe = os.path.join(ROOT, "ZeroConvertIME-windows", f"llama_{backend}", "llama-server.exe")

    def start(self):
        cmd = [
            self.server_exe,
            "-m", self.model_path,
            "--host", "127.0.0.1",
            "--port", str(self.port),
            "-ngl", str(self.n_gpu_layers),
            "-c", "2048",
            "--threads", "4",
            "--temp", "0.0",
        ]
        # Never attach to an already-running IME or another benchmark server.
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", self.port))
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        for _ in range(120):
            if self.proc.poll() is not None:
                raise RuntimeError("Owned llama-server exited during startup")
            try:
                req = urllib.request.Request(f"http://127.0.0.1:{self.port}/health")
                with urllib.request.urlopen(req, timeout=1) as resp:
                    if resp.status == 200:
                        return
            except Exception:
                time.sleep(0.25)
        raise RuntimeError(f"Failed to start llama-server for {self.model_path}")

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.kill()
                self.proc.wait()
            self.proc = None

    def complete(self, prompt: str, max_tokens: int = 96) -> dict:
        url = f"http://127.0.0.1:{self.port}/completion"
        data = json.dumps({
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": 0.0,
            "seed": 42,
            "cache_prompt": False,
            "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02", "\n"],
        }).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            if not isinstance(res.get("content"), str):
                raise RuntimeError(f"Missing model content: {res}")
            return res





def load_cases(path: str = TESTSET):
    with open(path, "r", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def evaluate_model(model_path: str, port: int, cases, n_gpu_layers: int = 99, backend: str = "vulkan") -> dict:
    srv = LlamaServerInstance(model_path, port, n_gpu_layers, backend)
    perfect_cnt = 0
    total_score = 0
    cat_stats = {}
    details = []
    latencies = []
    try:
        srv.start()
        for _ in range(3):
            srv.complete("\uEE02\uEE00キョウハイイテンキデス\uEE01")
        t0 = time.perf_counter()
        for c in cases:
            ctx = "" if c["left_context"] == "なし" else c["left_context"]
            kata = hira_to_kata(c["target"])
            cat = c.get("category", "その他")
            cat_stats.setdefault(cat, {"score": 0, "max": 0, "perfect": 0, "total": 0})
            cat_stats[cat]["max"] += 2
            cat_stats[cat]["total"] += 1

            prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
            started = time.perf_counter()
            response = srv.complete(prompt)
            latency_ms = (time.perf_counter() - started) * 1000
            latencies.append(latency_ms)
            out = response["content"].strip()
            score, reason = evaluate_output(out, c)
            if response.get("stopped_limit") or response.get("stop_type") == "limit":
                score, reason = 0, "NG (token limit)"
            total_score += score
            cat_stats[cat]["score"] += score
            if score == 2:
                perfect_cnt += 1
                cat_stats[cat]["perfect"] += 1
            details.append({
                "id": c.get("id"),
                "category": cat,
                "left_context": c["left_context"],
                "target": c["target"],
                "expected": c["expected"],
                "output": out,
                "score": score,
                "reason": reason,
                "latency_ms": round(latency_ms, 3),
                "stop_type": response.get("stop_type"),
                "stopped_limit": response.get("stopped_limit", False),
            })
        elapsed = time.perf_counter() - t0
    finally:
        srv.stop()
    total = len(cases)
    return {
        "model_path": model_path,
        "model_sha256": sha256(model_path),
        "runtime_sha256": sha256(srv.server_exe),
        "protocol": {"backend": backend, "context": 2048, "threads": 4,
                     "n_gpu_layers": n_gpu_layers, "temperature": 0.0,
                     "seed": 42, "cache_prompt": False, "max_tokens": 96, "warmup_requests": 3},
        "size_mb": round(os.path.getsize(model_path) / (1024 * 1024), 2) if os.path.exists(model_path) else None,
        "total_score": total_score,
        "max_score": total * 2,
        "accuracy_pct": round(total_score / (total * 2) * 100, 1),
        "perfect_count": perfect_cnt,
        "total_cases": total,
        "perfect_pct": round(perfect_cnt / total * 100, 1),
        "avg_latency_ms": round(statistics.fmean(latencies), 2),
        "median_latency_ms": round(statistics.median(latencies), 2),
        "p95_latency_ms": round(sorted(latencies)[min(len(latencies)-1, int(len(latencies)*0.95))], 2),
        "truncated_count": sum(d["reason"] == "NG (token limit)" for d in details),
        "elapsed_sec": round(elapsed, 1),
        "category_stats": cat_stats,
        "details": details,
    }


def sha256(path):
    with open(path, "rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def parse_model_specs(specs):
    parsed = []
    for spec in specs:
        if "=" in spec:
            name, path = spec.split("=", 1)
        else:
            name, path = os.path.basename(spec), spec
        parsed.append((name.strip(), path.strip()))
    return parsed



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", nargs="+", required=True,
                        help="'表示名=相対/絶対パス' の形式。複数指定可。")
    parser.add_argument("--backend", choices=["vulkan", "cuda", "cpu"], default="vulkan")
    parser.add_argument("--base-port", type=int, default=8300)
    parser.add_argument("--out", type=str, default=DEFAULT_OUT)
    parser.add_argument("--testset", type=str, default=TESTSET)
    parser.add_argument("--n-gpu-layers", type=int, default=99)
    args = parser.parse_args()

    cases = load_cases(args.testset)
    print(f"Loaded {len(cases)} test cases.", flush=True)

    results = {}
    for i, (name, path) in enumerate(parse_model_specs(args.models)):
        if not os.path.isabs(path):
            path = os.path.join(ROOT, path)
        print(f"\n=== Evaluating: {name} ({path}) ===", flush=True)
        try:
            res = evaluate_model(path, args.base_port + i, cases, args.n_gpu_layers, args.backend)
        except Exception as exc:
            print(f"  ERROR: {exc}", flush=True)
            continue
        res["testset_sha256"] = sha256(args.testset)
        results[name] = res
        print(f"  perfect {res['perfect_count']}/{res['total_cases']} "
              f"({res['perfect_pct']}%) | score {res['total_score']}/{res['max_score']} "
              f"({res['accuracy_pct']}%) | {res['avg_latency_ms']}ms", flush=True)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 80)
    print(f"{'モデル':<34} | {'正解数':>10} | {'正解率':>8} | {'得点率':>8} | {'サイズMB':>8}")
    print("-" * 80)
    for name, r in results.items():
        print(f"{name:<34} | {r['perfect_count']:>4}/{r['total_cases']:<5} | "
              f"{r['perfect_pct']:>7.1f}% | {r['accuracy_pct']:>7.1f}% | "
              f"{(r['size_mb'] or 0):>8.1f}")
    print("=" * 80)
    print(f"Saved: {args.out}")
    if len(results) != len(args.models):
        raise SystemExit("One or more models failed; results are incomplete")


if __name__ == "__main__":
    main()
