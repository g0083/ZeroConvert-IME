"""Verify separated corpus content and its recorded file identities."""

import argparse
import json
from pathlib import Path

from dataset_isolation import EVALUATION_FILES, POLICY, ROOT, sha256, validate_training_files


def check(directory):
    directory = Path(directory)
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest["policy"] != POLICY:
        raise ValueError("Unsupported isolation policy")
    expected_evaluation = {p.relative_to(ROOT).as_posix(): sha256(p) for p in EVALUATION_FILES}
    if {name.replace("\\", "/"): entry["sha256"] for name, entry in manifest["evaluation"].items()} != expected_evaluation:
        raise ValueError("Evaluation datasets changed; rebuild in a new directory")
    for name, entry in manifest["outputs"].items():
        if sha256(directory / name) != entry["sha256"]:
            raise ValueError(f"Output hash mismatch: {name}")
    checks = {}
    if (directory / "train.jsonl").exists() and (directory / "val.jsonl").exists():
        checks["root"] = validate_training_files(directory / "train.jsonl", directory / "val.jsonl")
    else:
        for family in ("typo", "distilled", "lora"):
            if (directory / family).exists():
                checks[family] = validate_training_files(directory / family / "train.jsonl", directory / family / "val.jsonl")
    return checks


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "artifacts/datasets/separated-v1")
    args = parser.parse_args()
    print(json.dumps(check(args.directory), indent=2))
