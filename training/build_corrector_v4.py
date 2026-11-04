"""Merge/export a completed corrector v4 and quantize with train-only calibration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from dataset_isolation import evaluation_keys, load_jsonl, sha256, validate_rows
from qwerty_input_contract import model_prompt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-name", default="corrector-v4-nano-20261030-r1")
    args = parser.parse_args()
    name = args.run_name
    if Path(name).name != name:
        raise ValueError("run-name must be a single file name")
    adapter = Path("artifacts/checkpoints") / f"{name}-lora"
    provenance = json.loads((adapter / "dataset-provenance.json").read_text(encoding="utf-8"))
    saved = json.loads((adapter / "run-manifest.json").read_text(encoding="utf-8"))
    if not provenance["training_completed"]:
        raise ValueError("Training did not complete")
    base = Path(saved["arguments"]["base_model"])
    for file, digest in saved["base"].items():
        if sha256(base / file) != digest:
            raise ValueError("Base weights/tokenizer changed since training")
    for key in ("train", "validation"):
        if sha256(provenance[key]["path"]) != provenance[key]["sha256"]:
            raise ValueError("Training inputs changed")
    train = load_jsonl(provenance["train"]["path"])
    val = load_jsonl(provenance["validation"]["path"])
    validate_rows(train, val, evaluation_keys())
    merged = Path("artifacts/checkpoints") / f"{name}-merged"
    f16 = Path("artifacts/models") / f"{name}-f16.gguf"
    directory = Path("artifacts/datasets") / f"{name}-imatrix"
    imatrix = Path("artifacts/models") / f"{name}.imatrix"
    targets = {"q4km": "Q4_K_M", "q6k": "Q6_K", "q8_0": "Q8_0", "iq4nl-emb8": "IQ4_NL"}
    outputs = {key: Path("artifacts/models") / f"{name}-{key}.gguf" for key in targets}
    if any(p.exists() for p in [merged, f16, directory, imatrix, *outputs.values()]):
        raise FileExistsError("Preserving existing export; use a fresh run name")
    directory.mkdir(parents=True)
    # Hash-order training rows so the first calibration chunks are not a single
    # contiguous phrase family. Neither validation nor benchmark text is used.
    ordered = sorted(train, key=lambda r: hashlib.sha256((r["prompt"] + r["completion"]).encode()).hexdigest())
    corpus = directory / "calibration.txt"
    contract = saved["arguments"].get("input_contract", "kana")
    corpus.write_text("\n".join(model_prompt(r["prompt"], r, contract) + r["completion"] + "</s>" for r in ordered) + "\n", encoding="utf-8")
    runtime = Path("ZeroConvertIME-windows/llama_vulkan")
    converter = Path("android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py")
    commands = [
        [sys.executable, "scripts/run.py", "training/export_typo_corrector.py", "--base_model", str(base),
         "--lora_dir", str(adapter), "--output_dir", str(merged)],
        [sys.executable, "scripts/run.py", str(converter), str(merged), "--outfile", str(f16), "--outtype", "f16"],
        [str(runtime / "llama-imatrix.exe"), "-m", str(f16), "-f", str(corpus), "-o", str(imatrix),
         "-ngl", "99", "-c", "512", "--chunks", "64", "-t", "4"],
    ]
    for key, quant in targets.items():
        command = [str(runtime / "llama-quantize.exe"), "--imatrix", str(imatrix)]
        if key in ("q6k", "iq4nl-emb8"):
            command += ["--token-embedding-type", "q8_0", "--output-tensor-type", "q8_0"]
        commands.append(command + [str(f16), str(outputs[key]), quant])
    manifest = {"status": "exporting", "commands": commands, "training": provenance,
                "base": saved["base"], "calibration_sha256": sha256(corpus),
                "tools": {str(p): sha256(p) for p in [Path(__file__), converter, runtime / "llama-imatrix.exe", runtime / "llama-quantize.exe"]}}
    manifest_path = directory / "manifest.json"
    def record():
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    record()
    try:
        for index, command in enumerate(commands):
            log = Path("artifacts/logs") / f"{name}-export-{index}.log"
            print(f"Running {index + 1}/{len(commands)}: {command}", flush=True)
            with log.open("w", encoding="utf-8") as stream:
                subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        manifest.update(status="completed", imatrix_sha256=sha256(imatrix),
                        outputs={key: {"path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size}
                                 for key, path in {"f16": f16, **outputs}.items()})
    except Exception as error:
        manifest.update(status="failed", error=str(error))
        raise
    finally:
        record()


if __name__ == "__main__":
    main()
