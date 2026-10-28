"""Validate and record the exact training inputs before loading model weights."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data"))
from dataset_isolation import (  # noqa: E402
    EVALUATION_FILES, POLICY, evaluation_keys, load_jsonl, sha256, validate_rows,
)


def checked_datasets(train_path, val_path, output_dir):
    train, val = load_jsonl(train_path), load_jsonl(val_path)
    checks = validate_rows(train, val, evaluation_keys())
    provenance = {
        "policy": POLICY, "checks": checks,
        "training_completed": False,
        "train": {"path": str(Path(train_path).resolve()), "sha256": sha256(train_path), "rows": len(train)},
        "validation": {"path": str(Path(val_path).resolve()), "sha256": sha256(val_path), "rows": len(val)},
        "evaluation": {p.name: sha256(p) for p in EVALUATION_FILES},
    }
    output = Path(output_dir)
    if output.exists() and any(output.iterdir()):
        raise FileExistsError(f"Use a new training output directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    (output / "dataset-provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return train, val


def record_training_completed(output_dir):
    path = Path(output_dir) / "dataset-provenance.json"
    provenance = json.loads(path.read_text(encoding="utf-8"))
    provenance["training_completed"] = True
    path.write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
