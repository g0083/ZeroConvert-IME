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
import ctypes
import hashlib
import re
import socket
import statistics
import json
import os
import subprocess
import sys
import time
import unicodedata
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
TESTSET = os.path.join(ROOT, 'data', 'kana_kanji_llm_testset_5x.jsonl')
DEFAULT_OUT = os.path.join(ROOT, 'artifacts', 'results', 'benchmark_kana_kanji_models.json')

DIGIT_RUN_RE = re.compile(r"[0-9０-９]+")
NAM_RESTORE_RE = re.compile(r"nam(?![a-wyz])")
NAM_FALLBACK_RE = re.compile(r"(?<![a-z])nam(?!(?:e[a-z]*|ing)(?![a-z]))")
QUANT_TOKEN_RE = re.compile(
    r"(?:^|[\s_\-/])(iq4(?:[_\-]?nl)?(?:[_\-]?emb8)?|q8(?:[_\-]?0)?|q4(?:[_\-]?k[_\-]?[ms]?)?)(?=$|[\s_\-/.])",
    re.IGNORECASE,
)


def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])


def normalize_ascii_digits(text: str) -> str:
    return unicodedata.normalize("NFKC", text)


def mask_digits_for_model(context: str, reading: str) -> tuple[str, str, list[str]]:
    digits = [normalize_ascii_digits(m.group(0)) for m in DIGIT_RUN_RE.finditer(reading)]
    masked_ctx = DIGIT_RUN_RE.sub("nam", context) if context else context
    if not digits:
        return masked_ctx, reading, []
    return masked_ctx, DIGIT_RUN_RE.sub("nam", reading), digits


def restore_digits_from_model(output: str, digits: list[str]) -> str:
    if not digits or "nam" not in output:
        return output
    idx = 0

    def _repl(m):
        nonlocal idx
        if idx < len(digits):
            val = digits[idx]
            idx += 1
            return val
        return m.group(0)

    out = NAM_RESTORE_RE.sub(_repl, output)
    if idx < len(digits) and "nam" in out:
        out = NAM_FALLBACK_RE.sub(_repl, out)
    return out


def compute_latency_metrics(latencies: list[float]) -> dict:
    if not latencies:
        return {
            "avg_latency_ms": 0.0,
            "p50_latency_ms": 0.0,
            "median_latency_ms": 0.0,
            "p95_latency_ms": 0.0,
            "p99_latency_ms": 0.0,
            "sla_over_30ms_count": 0,
            "sla_over_30ms_pct": 0.0,
            "sla_over_50ms_count": 0,
            "sla_over_50ms_pct": 0.0,
            "sla_over_100ms_count": 0,
            "sla_over_100ms_pct": 0.0,
        }
    n = len(latencies)
    sorted_lat = sorted(latencies)
    p50 = round(statistics.median(latencies), 2)
    p95 = round(sorted_lat[min(n - 1, int(n * 0.95))], 2)
    p99 = round(sorted_lat[min(n - 1, int(n * 0.99))], 2)
    over_30 = sum(1 for x in latencies if x > 30.0)
    over_50 = sum(1 for x in latencies if x > 50.0)
    over_100 = sum(1 for x in latencies if x > 100.0)
    return {
        "avg_latency_ms": round(statistics.fmean(latencies), 2),
        "p50_latency_ms": p50,
        "median_latency_ms": p50,
        "p95_latency_ms": p95,
        "p99_latency_ms": p99,
        "sla_over_30ms_count": over_30,
        "sla_over_30ms_pct": round(over_30 / n * 100, 1),
        "sla_over_50ms_count": over_50,
        "sla_over_50ms_pct": round(over_50 / n * 100, 1),
        "sla_over_100ms_count": over_100,
        "sla_over_100ms_pct": round(over_100 / n * 100, 1),
    }


def length_bucket_name(target: str) -> str:
    n = len(target)
    if n <= 8:
        return "short_4_8"
    if n <= 14:
        return "medium_9_14"
    if n <= 22:
        return "precommit_15_22"
    return "long_23_plus"


def check_structural_integrity(output: str, case: dict, input_digits: list[str], stopped_limit: bool) -> dict:
    out = output.strip().replace("\r", "").replace("\n", "")
    target = case.get("target", "")
    expected = case.get("expected", "")
    non_empty = bool(out)
    no_token_limit = not stopped_limit
    no_control_or_pua = not any((ord(ch) < 32) or (0xEE00 <= ord(ch) <= 0xEE02) for ch in out)
    no_runaway_length = len(out) <= max(len(target) * 2, len(expected) + 8)
    no_residual_nam = True if "nam" in target.lower() else (NAM_RESTORE_RE.search(out) is None)
    digits_preserved = True
    norm_expected = normalize_ascii_digits(expected)
    if input_digits and DIGIT_RUN_RE.search(norm_expected):
        norm_out = normalize_ascii_digits(out)
        pos = 0
        for d in input_digits:
            idx = norm_out.find(d, pos)
            if idx < 0:
                digits_preserved = False
                break
            pos = idx + len(d)
    nam_restored_ok = no_residual_nam and digits_preserved
    intact = non_empty and no_token_limit and no_control_or_pua and no_runaway_length and nam_restored_ok
    return {
        "intact": intact,
        "non_empty": non_empty,
        "no_token_limit": no_token_limit,
        "no_control_or_pua": no_control_or_pua,
        "no_runaway_length": no_runaway_length,
        "nam_restored_ok": nam_restored_ok,
    }


def _parse_quant_family(name: str) -> tuple[str | None, str]:
    base = os.path.basename(name)
    if base.lower().endswith(".gguf"):
        base = base[:-5]
    matches = list(QUANT_TOKEN_RE.finditer(base))
    if not matches:
        return None, re.sub(r"[\s_\-]+", " ", base.lower()).strip()
    m = matches[-1]
    token = m.group(1).lower()
    tier = "Q8" if token.startswith("q8") else "Q4"
    family = (base[:m.start(1)] + " " + base[m.end(1):]).lower()
    family = re.sub(r"[\s_\-]+", " ", family).strip()
    return tier, family


def compute_quantization_consistency(results: dict) -> dict:
    families: dict[str, dict[str, list[str]]] = {}
    for name in results:
        tier, family = _parse_quant_family(name)
        if tier:
            families.setdefault(family, {}).setdefault(tier, []).append(name)

    pairs_summary = {}
    for family, tiers in families.items():
        q8_names = tiers.get("Q8", [])
        q4_names = tiers.get("Q4", [])
        if not q8_names or not q4_names:
            continue
        q8_name = q8_names[0]
        for q4_name in q4_names:
            q8_details = results[q8_name].get("details", [])
            q4_details = results[q4_name].get("details", [])
            n = min(len(q8_details), len(q4_details))
            if n == 0:
                continue
            exact_cnt = 0
            score_agree_cnt = 0
            q8_only = []
            q4_only = []
            diff_cases = []
            for d8, d4 in zip(q8_details[:n], q4_details[:n]):
                o8 = d8.get("output", "")
                o4 = d4.get("output", "")
                s8 = d8.get("score", 0)
                s4 = d4.get("score", 0)
                if o8 == o4:
                    exact_cnt += 1
                else:
                    diff_cases.append({
                        "id": d8.get("id"),
                        "category": d8.get("category"),
                        "target": d8.get("target"),
                        "expected": d8.get("expected"),
                        "q8_output": o8,
                        "q4_output": o4,
                    })
                if (s8 == 2) == (s4 == 2):
                    score_agree_cnt += 1
                elif s8 == 2:
                    q8_only.append(d8.get("id"))
                else:
                    q4_only.append(d4.get("id"))

            pair_label = f"{q8_name} vs {q4_name}"
            base_metrics = {
                "quant_pair": pair_label,
                "family": family,
                "total_cases": n,
                "exact_output_match_count": exact_cnt,
                "exact_output_match_pct": round(exact_cnt / n * 100, 1),
                "score_agreement_count": score_agree_cnt,
                "score_agreement_pct": round(score_agree_cnt / n * 100, 1),
                "q8_only_perfect_ids": q8_only,
                "q4_only_perfect_ids": q4_only,
                "output_diff_cases": diff_cases,
            }
            pairs_summary[pair_label] = base_metrics
            results[q8_name]["quantization_consistency"] = {**base_metrics, "paired_with": q4_name}
            results[q4_name]["quantization_consistency"] = {**base_metrics, "paired_with": q8_name}
    return pairs_summary


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

    def get_memory_mb(self) -> dict:
        if not self.proc or self.proc.poll() is not None:
            return {"rss_mb": None, "peak_rss_mb": None}
        if os.name == "nt":
            try:
                import ctypes.wintypes as wintypes

                class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
                    _fields_ = [
                        ("cb", wintypes.DWORD),
                        ("PageFaultCount", wintypes.DWORD),
                        ("PeakWorkingSetSize", ctypes.c_size_t),
                        ("WorkingSetSize", ctypes.c_size_t),
                        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                        ("PagefileUsage", ctypes.c_size_t),
                        ("PeakPagefileUsage", ctypes.c_size_t),
                    ]

                k32 = ctypes.windll.kernel32
                k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
                k32.OpenProcess.restype = wintypes.HANDLE
                k32.CloseHandle.argtypes = [wintypes.HANDLE]
                k32.CloseHandle.restype = wintypes.BOOL
                k32.K32GetProcessMemoryInfo.argtypes = [
                    wintypes.HANDLE,
                    ctypes.POINTER(PROCESS_MEMORY_COUNTERS),
                    wintypes.DWORD,
                ]
                k32.K32GetProcessMemoryInfo.restype = wintypes.BOOL

                handle = k32.OpenProcess(0x1010, False, self.proc.pid)
                if not handle:
                    handle = k32.OpenProcess(0x1000, False, self.proc.pid)
                if handle:
                    try:
                        pmc = PROCESS_MEMORY_COUNTERS(cb=ctypes.sizeof(PROCESS_MEMORY_COUNTERS))
                        if k32.K32GetProcessMemoryInfo(handle, ctypes.byref(pmc), pmc.cb):
                            mb = 1024 * 1024
                            return {
                                "rss_mb": round(pmc.WorkingSetSize / mb, 2),
                                "peak_rss_mb": round(pmc.PeakWorkingSetSize / mb, 2),
                            }
                    finally:
                        k32.CloseHandle(handle)
            except Exception:
                return {"rss_mb": None, "peak_rss_mb": None}
        return {"rss_mb": None, "peak_rss_mb": None}

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

    def conversion_prompt(self, context, katakana_reading):
        return f"\uEE02{context}\uEE00{katakana_reading}\uEE01"





def load_cases(path: str = TESTSET):
    with open(path, "r", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def evaluate_model(model_path: str, port: int, cases, n_gpu_layers: int = 99, backend: str = "vulkan", server_factory=None) -> dict:
    srv = (server_factory or LlamaServerInstance)(model_path, port, n_gpu_layers, backend)
    perfect_cnt = 0
    total_score = 0
    structural_intact_cnt = 0
    nam_case_cnt = 0
    nam_restored_ok_cnt = 0
    cat_stats = {}
    cat_latencies = {}
    bucket_stats = {}
    bucket_latencies = {}
    details = []
    latencies = []
    mem_info = {"rss_mb": None, "peak_rss_mb": None}
    try:
        srv.start()
        for _ in range(3):
            srv.complete(srv.conversion_prompt("", "キョウハイイテンキデス"))
        t0 = time.perf_counter()
        for c in cases:
            ctx = "" if c["left_context"] == "なし" else c["left_context"]
            kata = hira_to_kata(c["target"])
            cat = c.get("category", "その他")
            bucket = length_bucket_name(c["target"])
            cat_stats.setdefault(cat, {"score": 0, "max": 0, "perfect": 0, "total": 0})
            cat_stats[cat]["max"] += 2
            cat_stats[cat]["total"] += 1
            cat_latencies.setdefault(cat, [])
            bucket_stats.setdefault(bucket, {"score": 0, "max": 0, "perfect": 0, "total": 0})
            bucket_stats[bucket]["max"] += 2
            bucket_stats[bucket]["total"] += 1
            bucket_latencies.setdefault(bucket, [])

            masked_ctx, masked_kata, input_digits = mask_digits_for_model(ctx, kata)
            prompt = srv.conversion_prompt(masked_ctx, masked_kata)
            started = time.perf_counter()
            response = srv.complete(prompt)
            out = restore_digits_from_model(response["content"].strip(), input_digits)
            latency_ms = (time.perf_counter() - started) * 1000
            latencies.append(latency_ms)
            cat_latencies[cat].append(latency_ms)
            bucket_latencies[bucket].append(latency_ms)

            stopped_limit = bool(response.get("stopped_limit") or response.get("stop_type") == "limit")
            score, reason = evaluate_output(out, c)
            if stopped_limit:
                score, reason = 0, "NG (token limit)"
            integrity = check_structural_integrity(out, c, input_digits, stopped_limit)
            if integrity["intact"]:
                structural_intact_cnt += 1
            if input_digits:
                nam_case_cnt += 1
                if integrity["nam_restored_ok"]:
                    nam_restored_ok_cnt += 1

            total_score += score
            cat_stats[cat]["score"] += score
            bucket_stats[bucket]["score"] += score
            if score == 2:
                perfect_cnt += 1
                cat_stats[cat]["perfect"] += 1
                bucket_stats[bucket]["perfect"] += 1
            details.append({
                "id": c.get("id"),
                "category": cat,
                "length_bucket": bucket,
                "target_len": len(c["target"]),
                "left_context": c["left_context"],
                "target": c["target"],
                "expected": c["expected"],
                "output": out,
                "score": score,
                "reason": reason,
                "latency_ms": round(latency_ms, 3),
                "stop_type": response.get("stop_type"),
                "stopped_limit": response.get("stopped_limit", False),
                "structural_intact": integrity["intact"],
                "nam_restored_ok": integrity["nam_restored_ok"],
            })
        elapsed = time.perf_counter() - t0
        if hasattr(srv, "get_memory_mb"):
            mem_info = srv.get_memory_mb()
    finally:
        srv.stop()

    for group_stats, group_latencies in ((cat_stats, cat_latencies), (bucket_stats, bucket_latencies)):
        for key, st in group_stats.items():
            lat_metrics = compute_latency_metrics(group_latencies.get(key, []))
            st["perfect_pct"] = round(st["perfect"] / st["total"] * 100, 1) if st["total"] else 0.0
            st["avg_latency_ms"] = lat_metrics["avg_latency_ms"]
            st["p95_latency_ms"] = lat_metrics["p95_latency_ms"]

    total = len(cases)
    size_mb = round(os.path.getsize(model_path) / (1024 * 1024), 2) if os.path.exists(model_path) else None
    perfect_pct = round(perfect_cnt / total * 100, 1) if total else 0.0
    rss_mb = mem_info.get("rss_mb")
    peak_rss_mb = mem_info.get("peak_rss_mb")
    lat_summary = compute_latency_metrics(latencies)
    return {
        "model_path": model_path,
        "model_sha256": sha256(model_path) if os.path.exists(model_path) else None,
        "runtime_sha256": sha256(srv.server_exe) if getattr(srv, "server_exe", None) and os.path.exists(srv.server_exe) else None,
        "protocol": {"backend": backend, "context": 2048, "threads": 4,
                     "n_gpu_layers": n_gpu_layers, "temperature": 0.0,
                     "seed": 42, "cache_prompt": False, "max_tokens": 96, "warmup_requests": 3},
        "size_mb": size_mb,
        "rss_mb": rss_mb,
        "peak_rss_mb": peak_rss_mb,
        "accuracy_per_file_mb": round(perfect_pct / size_mb, 3) if size_mb else None,
        "accuracy_per_ram_mb": round(perfect_pct / peak_rss_mb, 3) if peak_rss_mb else None,
        "total_score": total_score,
        "max_score": total * 2,
        "accuracy_pct": round(total_score / (total * 2) * 100, 1) if total else 0.0,
        "perfect_count": perfect_cnt,
        "total_cases": total,
        "perfect_pct": perfect_pct,
        **lat_summary,
        "structural_intact_count": structural_intact_cnt,
        "structural_integrity_pct": round(structural_intact_cnt / total * 100, 1) if total else 0.0,
        "nam_case_count": nam_case_cnt,
        "nam_restored_ok_count": nam_restored_ok_cnt,
        "nam_restoration_pct": round(nam_restored_ok_cnt / nam_case_cnt * 100, 1) if nam_case_cnt else None,
        "truncated_count": sum(d["reason"] == "NG (token limit)" for d in details),
        "elapsed_sec": round(elapsed, 1),
        "category_stats": cat_stats,
        "length_bucket_stats": bucket_stats,
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
              f"({res['perfect_pct']}%) | p50 {res['p50_latency_ms']}ms "
              f"p95 {res['p95_latency_ms']}ms p99 {res['p99_latency_ms']}ms | "
              f"RSS {res['peak_rss_mb']}MB | intact {res['structural_integrity_pct']}%", flush=True)

    quant_pairs = compute_quantization_consistency(results)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 108)
    print(f"{'モデル':<28} | {'正解数':>9} | {'正解率':>7} | {'p50/p95/p99ms':>17} | {'>30/>50ms':>11} | {'RAM(MB)':>8} | {'健全率':>6}")
    print("-" * 108)
    for name, r in results.items():
        lat_str = f"{r['p50_latency_ms']:.1f}/{r['p95_latency_ms']:.1f}/{r['p99_latency_ms']:.1f}"
        sla_str = f"{r['sla_over_30ms_pct']:.1f}/{r['sla_over_50ms_pct']:.1f}%"
        ram_str = f"{r['peak_rss_mb']:.1f}" if r.get("peak_rss_mb") is not None else "N/A"
        print(f"{name:<28} | {r['perfect_count']:>4}/{r['total_cases']:<4} | "
              f"{r['perfect_pct']:>6.1f}% | {lat_str:>17} | {sla_str:>11} | "
              f"{ram_str:>8} | {r['structural_integrity_pct']:>5.1f}%")
    if quant_pairs:
        print("-" * 108)
        for pair_label, q in quant_pairs.items():
            print(f"Quant一致率 [{pair_label}]: 出力完全一致 {q['exact_output_match_count']}/{q['total_cases']} "
                  f"({q['exact_output_match_pct']}%), 正誤一致 {q['score_agreement_pct']}%")
    print("=" * 108)
    print(f"Saved: {args.out}")
    if len(results) != len(args.models):
        raise SystemExit("One or more models failed; results are incomplete")


if __name__ == "__main__":
    main()
