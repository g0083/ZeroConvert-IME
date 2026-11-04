"""Evaluate actual correction policy using prompt-matched model response replay.

Normal outputs are independently generated and frozen; gold never enters model
input. Replay timings exclude model inference and are never latency results.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

from benchmark_kana_kanji_models import LlamaServerInstance
from dataset_isolation import load_jsonl, sha256
from generate_typo_dataset import hira_to_kata
from qwerty_input_contract import model_prompt

RUNNER = Path("artifacts/build/corrector-qwerty-20261030/debug/examples/evaluate_corrector.exe")
DICTIONARY = Path("ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin")


def cohort(rows, quotas=None):
    quotas = quotas or {"repair": 192, "keep": 128, "wait": 64}
    selected = []
    for action, limit in quotas.items():
        groups = defaultdict(list)
        for row in rows:
            if row.get("preservation_canary"):
                continue
            if row["expected_action"] == action:
                groups[row["source_group"]].append(row)
        for group in groups:
            groups[group].sort(key=lambda r: hashlib.sha256(r["id"].encode()).hexdigest())
        order = sorted(groups, key=lambda g: hashlib.sha256((action + g).encode()).hexdigest())
        count, round_index = 0, 0
        while count < limit and any(len(v) > round_index for v in groups.values()):
            for group in order:
                if len(groups[group]) > round_index:
                    selected.append(groups[group][round_index])
                    count += 1
                    if count == limit:
                        break
            round_index += 1
    canaries = sorted((r for r in rows if r.get("preservation_canary")), key=lambda r: hashlib.sha256(r["id"].encode()).hexdigest())[:96]
    return selected + canaries


def write_json(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def policy(rows, runner=RUNNER):
    # Temporary files are created under the artifact root, never source dirs.
    temp_root = Path("artifacts/results/qwerty-policy-tmp").resolve()
    temp_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temp_root) as directory:
        path = Path(directory).resolve()
        if not path.is_relative_to(temp_root):
            raise ValueError("Unexpected temporary workspace")
        cases, output = path / "cases.jsonl", path / "report.json"
        cases.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        result = subprocess.run([str(runner.resolve()), str(cases), "0", "0", str(DICTIONARY.resolve()), str(output), "--replay"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if result.returncode:
            raise RuntimeError(result.stderr[-5000:])
        report = json.loads(output.read_text(encoding="utf-8"))
    return report


def summary(report, rows):
    lookup = {r["id"]: r for r in rows}
    counts = Counter()
    for item in report["cases"]:
        row = lookup[item["id"]]
        action, output = row["expected_action"], item["correction"]
        counts[action + "_total"] += 1
        if row.get("preservation_canary"):
            counts["preservation_total"] += 1
            counts["preservation_proposed"] += int(output is not None)
        if action == "repair":
            if output is not None and output in [row["expected"], *(row.get("acceptable") or [])]:
                counts["repair_correct"] += 1
            elif output is not None:
                counts["repair_wrong"] += 1
            else:
                counts["repair_missing"] += 1
        elif output is not None:
            counts[action + "_proposed"] += 1
        counts["auto_applied"] += int(item["auto_apply"])
        counts["wrong_auto_applied"] += int(item["auto_apply"] and (action != "repair" or output not in [row["expected"], *(row.get("acceptable") or [])]))
        counts["crashed"] += int(item["crashed"])
    tp, fp, fn = counts["repair_correct"], counts["repair_wrong"] + counts["keep_proposed"] + counts["wait_proposed"], counts["repair_total"] - counts["repair_correct"]
    counts = dict(counts)
    counts["case_f0_5"] = 1.25 * tp / (1.25 * tp + .25 * fn + fp) if tp + fp + fn else 0.
    return counts


def replay_responses(fixture, responses, runner=RUNNER):
    rows = [{**row, "responses": {call["prompt"]: responses[(row["id"], call["prompt"])] for call in plan["calls"]}}
            for row, plan in zip(fixture["cases"], fixture["plan"]["cases"])]
    report = policy(rows, runner)
    for case in report["cases"]:
        if any(call["response"] is None for call in case["calls"]):
            raise ValueError("A policy request was not answered by its exact model prompt")
    return {"summary": summary(report, fixture["cases"]), "report": report}


def complete(server, prompt, budget):
    data = {"prompt": prompt, "n_predict": budget, "temperature": 0., "seed": 42, "cache_prompt": False,
            "stop": ["</s>", "<s>", "\n", "\r", "\uee00", "\uee01", "\uee02"]}
    request = urllib.request.Request(f"http://127.0.0.1:{server.port}/completion", data=json.dumps(data).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as stream:
        return json.load(stream)


def evaluate_gguf(fixture, model, port=8540, input_contract="kana"):
    server = LlamaServerInstance(str(Path(model).resolve()), port, 0, "cpu")
    responses = {}
    model_requests = []
    try:
        server.start()
        for row in fixture["plan"]["cases"]:
            observation = next(r for r in fixture["cases"] if r["id"] == row["id"])
            for call in row["calls"]:
                budget = call.get("request", {}).get("n_predict", min(384, max(32, len(row["input"])*3+16)))
                actual_prompt = model_prompt(call["prompt"], observation, input_contract)
                response = complete(server, actual_prompt, budget)
                responses[(row["id"], call["prompt"])] = response
                model_requests.append({"id": row["id"], "prompt": actual_prompt, "n_predict": budget, "response": response})
    finally:
        server.stop()
    result = replay_responses(fixture, responses)
    result["model"] = {"path": str(model), "sha256": sha256(model)}
    result["input_contract"] = input_contract
    result["model_requests"] = model_requests
    result["timing_scope"] = "No latency comparison: model responses generated first, then native policy replay"
    return result


def prepare(dataset, split, out, normal, limit=True):
    if out.exists():
        raise FileExistsError(out)
    rows = load_jsonl(dataset / f"{split}.jsonl")
    if limit:
        rows = cohort(rows)
    server = LlamaServerInstance(str(normal.resolve()), 8541, 0, "cpu")
    normal_errors = 0
    try:
        server.start()
        for i, row in enumerate(rows):
            prompt = ("\uee02" + row["context"] if row["context"] else "") + "\uee00" + hira_to_kata(row["native_observed"]) + "\uee01"
            response = complete(server, prompt, 256)
            if not response.get("content"):
                normal_errors += 1
                response = {**response, "content": row["native_observed"], "explicit_normal_fallback_to_observed": True}
            row["normal_response"] = response
            if i % 100 == 0:
                print("normal", i, len(rows), flush=True)
    finally:
        server.stop()
    plan = policy(rows)
    result = {"schema": "native-qwerty-policy-fixture-v1", "cases": rows, "plan": plan,
        "normal_model": {"path": str(normal), "sha256": sha256(normal)}, "normal_empty_responses": normal_errors,
        "dataset_manifest_sha256": sha256(dataset / "manifest.json"), "case_file_sha256": sha256(dataset / f"{split}.jsonl"),
        "runner_sha256": sha256(RUNNER), "dictionary_sha256": sha256(DICTIONARY), "split": split,
        "scope": "Native correction policy; independent frozen normal outputs; synthetic native key replay; no TSF/UI"}
    out.parent.mkdir(parents=True, exist_ok=True)
    write_json(out, result)
    print("prepared", len(rows), Counter(r["expected_action"] for r in rows), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--prepare", type=Path)
    p.add_argument("--split", choices=("val", "test"), default="val")
    p.add_argument("--all", action="store_true")
    p.add_argument("--normal", type=Path, default=Path("artifacts/models/zerocon/zerocon-model-standard.gguf"))
    p.add_argument("--fixture", type=Path)
    p.add_argument("--model", type=Path)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    if a.prepare:
        prepare(a.prepare, a.split, a.out, a.normal, not a.all)
    else:
        if a.out.exists():
            raise FileExistsError(a.out)
        fixture = json.loads(a.fixture.read_text(encoding="utf-8"))
        result = evaluate_gguf(fixture, a.model)
        result["fixture_sha256"] = sha256(a.fixture)
        write_json(a.out, result)
        print(json.dumps(result["summary"], indent=2), flush=True)
