"""Build new isolated corpora without overwriting legacy data or model weights."""

import argparse
import json
from collections import Counter
from pathlib import Path

from dataset_isolation import (
    EVALUATION_FILES, POLICY, ROOT, evaluation_keys, load_jsonl,
    separated_rows, sha256, validate_rows,
)

SOURCES = (
    ("data/typo_dataset_train.jsonl", "typo"),
    ("data/typo_dataset_val.jsonl", "typo"),
    ("data/typo_dataset_train_30k.jsonl", "typo"),
    ("data/typo_dataset_val_30k.jsonl", "typo"),
    ("data/distilled_train.jsonl", "distilled"),
    ("data/distilled_val.jsonl", "distilled"),
)


def build(output, seed="20261027"):
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"Use a new output directory; preserving existing files: {output}")
    rows, inputs, seen = [], {}, set()
    for relative, family in SOURCES:
        path = ROOT / relative
        source = load_jsonl(path)
        inputs[relative] = {"sha256": sha256(path), "rows": len(source)}
        for index, row in enumerate(source, 1):
            if not row.get("prompt") or not row.get("completion"):
                raise ValueError(f"Missing prompt/completion: {relative}:{index}")
            # Retain duplicates during grouping: metadata may link other siblings.
            rows.append({**row, "origin": relative, "origin_row": index, "family": family})
    reserved = evaluation_keys()
    train, val, excluded = separated_rows(rows, reserved, seed=seed)
    # Deduplicate only after all source identities have been connected.
    splits = []
    for split in (train, val):
        unique = []
        for row in split:
            key = (row["family"], row["prompt"], row["completion"])
            if key not in seen:
                seen.add(key)
                unique.append(row)
        splits.append(unique)
    train, val = splits
    checks = validate_rows(train, val, reserved)
    corpora = {}
    for family in ("typo", "distilled", "lora"):
        tr = [r for r in train if family == "lora" or r["family"] == family]
        va = [r for r in val if family == "lora" or r["family"] == family]
        validate_rows(tr, va, reserved)
        corpora[family] = (tr, va)
    output.mkdir(parents=True)
    manifest = {
        "policy": POLICY, "seed": seed, "validation_fraction": 0.1,
        "inputs": inputs,
        "evaluation": {p.relative_to(ROOT).as_posix(): {"sha256": sha256(p), "rows": len(load_jsonl(p))}
                       for p in EVALUATION_FILES},
        "excluded_rows": len(excluded),
        "excluded_by_origin": dict(Counter(r["origin"] for r in excluded)),
        "checks": checks, "outputs": {},
        "limitations": "Existing weights are unchanged. These are development benchmarks; base-model pretraining overlap is unknown.",
    }
    for family, (tr, va) in corpora.items():
        (output / family).mkdir()
        for name, subset in (("train", tr), ("val", va)):
            path = output / family / f"{name}.jsonl"
            path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in subset), encoding="utf-8")
            manifest["outputs"][f"{family}/{name}.jsonl"] = {
                "rows": len(subset), "groups": len({r["source_group"] for r in subset}), "sha256": sha256(path),
            }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts/datasets/separated-v1")
    parser.add_argument("--seed", default="20261027")
    args = parser.parse_args()
    print(json.dumps(build(args.out, args.seed), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
