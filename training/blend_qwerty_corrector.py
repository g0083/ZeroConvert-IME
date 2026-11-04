"""Validation-only WiSE-FT interpolation between two compatible correctors."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

from dataset_isolation import sha256
from qwerty_corrector_policy import evaluate_gguf, write_json
from select_qwerty_corrector import eligible


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", required=True)
    p.add_argument("--parent", type=Path, required=True)
    p.add_argument("--adapted", type=Path, required=True)
    p.add_argument("--imatrix", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--port", type=int, default=8560)
    a = p.parse_args()
    if Path(a.run_name).name != a.run_name:
        raise ValueError("Expected a single run name")
    out = Path("artifacts/results") / a.run_name
    if out.exists():
        raise FileExistsError(out)
    fixture = json.loads(a.fixture.read_text(encoding="utf-8"))
    baseline = json.loads(a.baseline.read_text(encoding="utf-8"))
    if fixture["split"] != "val" or baseline["fixture_sha256"] != sha256(a.fixture):
        raise ValueError("Expected matching validation fixture and baseline")
    parent_tok = AutoTokenizer.from_pretrained(a.parent, local_files_only=True)
    adapted_tok = AutoTokenizer.from_pretrained(a.adapted, local_files_only=True)
    if parent_tok.get_vocab() != adapted_tok.get_vocab() or parent_tok.backend_tokenizer.to_str() != adapted_tok.backend_tokenizer.to_str():
        raise ValueError("Tokenizer mismatch")
    torch.set_num_threads(4)
    parent = AutoModelForCausalLM.from_pretrained(a.parent, dtype=torch.float32, local_files_only=True)
    adapted = AutoModelForCausalLM.from_pretrained(a.adapted, dtype=torch.float32, local_files_only=True)
    left, right = dict(parent.named_parameters()), dict(adapted.named_parameters())
    if left.keys() != right.keys() or any(left[k].shape != right[k].shape for k in left):
        raise ValueError("Incompatible model tensors")
    configs = [m.config.to_dict() for m in (parent, adapted)]
    for key in configs[0]:
        if key not in ("_name_or_path", "transformers_version") and configs[0][key] != configs[1].get(key):
            raise ValueError(f"Model configuration mismatch: {key}")
    source = {str(d): {f.name: sha256(f) for f in d.iterdir() if f.suffix in (".safetensors", ".json")} for d in (a.parent, a.adapted)}
    original = {k: v.detach().clone() for k, v in left.items()}
    out.mkdir(parents=True)
    results = {}
    # Declare this grid before evaluating any blend. No test rows are loaded.
    alphas = (.25, .5, .75)
    write_json(out / "plan.json", {"alphas": alphas, "source": source,
        "fixture_sha256": sha256(a.fixture), "imatrix_sha256": sha256(a.imatrix),
        "script_sha256": sha256(__file__), "status": "started"})
    for alpha in alphas:
        name = f"{a.run_name}-a{int(alpha*100)}"
        merged = Path("artifacts/checkpoints") / f"{name}-merged"
        f16 = Path("artifacts/models") / f"{name}-f16.gguf"
        q4 = Path("artifacts/models") / f"{name}-q4km.gguf"
        if any(f.exists() for f in (merged, f16, q4)):
            raise FileExistsError(name)
        with torch.no_grad():
            for key, value in left.items():
                value.copy_(torch.lerp(original[key], right[key], alpha))
        parent.save_pretrained(merged)
        parent_tok.save_pretrained(merged)
        commands = [[sys.executable, "scripts/run.py", "android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py",
            str(merged), "--outfile", str(f16), "--outtype", "f16"],
            ["ZeroConvertIME-windows/llama_vulkan/llama-quantize.exe", "--imatrix", str(a.imatrix), str(f16), str(q4), "Q4_K_M"]]
        for i, command in enumerate(commands):
            with (Path("artifacts/logs") / f"{name}-export-{i}.log").open("w", encoding="utf-8") as stream:
                subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True)
        report = evaluate_gguf(fixture, q4, port=a.port)
        report["model"]["bytes"] = q4.stat().st_size
        write_json(out / f"a{int(alpha*100)}.json", report)
        results[str(alpha)] = {"model": report["model"], "summary": report["summary"],
            "eligible": eligible(report["summary"], baseline["summary"])}
        print(alpha, results[str(alpha)], flush=True)
    candidates = [k for k in results if results[k]["eligible"]]
    chosen = max(candidates, key=lambda k: results[k]["summary"]["case_f0_5"]) if candidates else None
    write_json(out / "selection.json", {"selected": chosen, "models": results, "baseline": baseline["summary"],
        "fixture_sha256": sha256(a.fixture), "source": source, "status": "completed",
        "policy": "Validation-only F0.5; no increase in normal, pending, wrong automatic or preservation-canary proposals"})
    print("Selected", chosen, flush=True)


if __name__ == "__main__":
    main()
