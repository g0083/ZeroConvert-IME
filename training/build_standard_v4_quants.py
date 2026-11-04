"""Build train-only calibration, imatrix and Q8/IQ4 models without replacing artifacts."""

import argparse
import json
from pathlib import Path
import subprocess

from dataset_isolation import evaluation_keys, load_jsonl, sha256, validate_rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-name", default="standard-v4-nano-transfer")
    args = parser.parse_args()
    if Path(args.run_name).name != args.run_name:
        parser.error("run-name must be a single file name")
    model = Path("artifacts/models") / f"{args.run_name}-f16.gguf"
    provenance = Path("artifacts/checkpoints") / f"{args.run_name}-lora/dataset-provenance.json"
    saved = json.loads(provenance.read_text(encoding="utf-8"))
    train_path, val_path = saved["train"]["path"], saved["validation"]["path"]
    if not saved["training_completed"]:
        raise ValueError("Training did not complete")
    for key, path in (("train", train_path), ("validation", val_path)):
        if sha256(path) != saved[key]["sha256"]:
            raise ValueError("Training input changed since training")
    train, val = load_jsonl(train_path), load_jsonl(val_path)
    validate_rows(train, val, evaluation_keys())
    directory = Path("artifacts/datasets") / f"{args.run_name}-imatrix"
    imatrix = Path("artifacts/models") / f"{args.run_name}.imatrix"
    targets = {"Q8_0": Path("artifacts/models") / f"{args.run_name}-q8_0.gguf",
               "IQ4_NL": Path("artifacts/models") / f"{args.run_name}-iq4nl-emb8.gguf"}
    if directory.exists() or imatrix.exists() or any(p.exists() for p in targets.values()):
        raise FileExistsError("Calibration or quantized artifacts already exist")
    directory.mkdir(parents=True)
    lines = []
    for row in train:
        completion = row["completion"].replace(" ", "\u3000")
        lines.append(row["prompt"] + completion + "</s>")
        if " " in row["prompt"]:
            lines.append(row["prompt"].replace(" ", "\u3000") + completion + "</s>")
    corpus = directory / "calibration.txt"
    corpus.write_text("\n".join(lines * 2) + "\n", encoding="utf-8")
    runtime = Path("ZeroConvertIME-windows/llama_vulkan")
    commands = [[str(runtime / "llama-imatrix.exe"), "-m", str(model), "-f", str(corpus),
                 "-o", str(imatrix), "-ngl", "99", "--chunks", "64"]]
    for quant, target in targets.items():
        command = [str(runtime / "llama-quantize.exe"), "--imatrix", str(imatrix)]
        if quant == "IQ4_NL":
            command += ["--token-embedding-type", "q8_0", "--output-tensor-type", "q8_0"]
        commands.append(command + [str(model), str(target), quant])
    for command in commands:
        print(command, flush=True)
        subprocess.run(command, check=True)
    manifest = {"commands": commands, "train_sha256": sha256(train_path),
                "calibration_sha256": sha256(corpus), "source_sha256": sha256(model),
                "imatrix_sha256": sha256(imatrix),
                "runtime": {p.name: sha256(p) for p in (runtime / "llama-imatrix.exe", runtime / "llama-quantize.exe")},
                "outputs": {str(p): sha256(p) for p in targets.values()}}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
