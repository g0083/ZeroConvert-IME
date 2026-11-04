"""Re-audit the latest corrector corpus while preserving its existing split.

Remove whole related groups when newly reserved evaluations overlap. Validation
also excludes groups exposed to Nano v4's known supervised train/validation data.
This cannot audit the original zenz pretraining corpus.
"""
import argparse
import json
from pathlib import Path
import re

from dataset_isolation import evaluation_keys, load_jsonl, normalize, sha256, source_keys, validate_rows


def is_clean(row):
    # Augmentation inherited is_clean/typo_type from the source; inspect text.
    text = row["prompt"].split("\uee00", 1)[1].removesuffix("\uee01")
    return normalize(text) in {
        normalize(row.get("target_reading", "")), normalize(row["completion"]),
    }


def filter_groups(rows, reserved):
    blocked = {r["source_group"] for r in rows if source_keys(r) & reserved}
    return [r for r in rows if r["source_group"] not in blocked], blocked


def valid_example(row):
    # U+EE08 etc. belong to other tasks' annotated targets, not surface repair.
    # Do not guess the intended plain-text answer by stripping those markers.
    return bool(re.fullmatch(r"(?:\uee02[^\ue000-\uf8ff]*)?\uee00[^\ue000-\uf8ff]+\uee01", row["prompt"])) and not any(
        "\ue000" <= c <= "\uf8ff" for c in row["completion"]
    )


def build(source, output, parent_source):
    if output.exists():
        raise FileExistsError(f"Preserving existing dataset: {output}")
    paths = [source / f"{split}.jsonl" for split in ("train", "val")]
    parent_paths = [parent_source / f"{split}.jsonl" for split in ("train", "val")]
    original = [load_jsonl(p) for p in paths]
    for rows in original:
        if any(not r.get("source_group") for r in rows):
            raise ValueError("Existing source groups are required")
    reserved = evaluation_keys()
    # Block a connected source group on either side before retaining either side.
    _, blocked = filter_groups(original[0] + original[1], reserved)
    train, val = [[r for r in rows if r["source_group"] not in blocked] for rows in original]
    parent_rows = [r for p in parent_paths for r in load_jsonl(p)]
    parent_keys = set().union(*(source_keys(r) for r in parent_rows))
    val, parent_blocked = filter_groups(val, parent_keys)
    invalid = {"train": sum(not valid_example(r) for r in train),
               "val": sum(not valid_example(r) for r in val)}
    train, val = [[r for r in rows if valid_example(r)] for rows in (train, val)]
    checks = validate_rows(train, val, reserved)
    checks["validation_parent_supervised_shared_keys"] = len(
        set().union(*(source_keys(r) for r in val)) & parent_keys
    )
    if checks["validation_parent_supervised_shared_keys"]:
        raise ValueError(checks)
    output.mkdir(parents=True)
    manifest = {
        "sources": {str(p): sha256(p) for p in paths},
        "known_parent_supervised_sources": {str(p): sha256(p) for p in parent_paths},
        "removed_evaluation_groups": sorted(blocked),
        "removed_parent_exposed_validation_groups": sorted(parent_blocked),
        "removed_non_surface_format_rows": invalid,
        "original_rows": dict(zip(("train", "val"), map(len, original))),
        "checks": checks, "outputs": {},
        "limitations": "Original zenz pretraining exposure is unknown; development evaluations are not an unseen final test.",
    }
    for split, rows in (("train", train), ("val", val)):
        path = output / f"{split}.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        manifest["outputs"][split] = {
            "rows": len(rows), "sha256": sha256(path),
            "clean_rows": sum(is_clean(r) for r in rows),
            "groups": len({r["source_group"] for r in rows}),
        }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, default=Path("artifacts/datasets/corrector-v2-20261027"))
    p.add_argument("--parent-source", type=Path, default=Path("artifacts/datasets/standard-v3-knowledge"))
    p.add_argument("--out", type=Path, default=Path("artifacts/datasets/corrector-v4-20261030"))
    args = p.parse_args()
    print(json.dumps(build(args.source, args.out, args.parent_source), ensure_ascii=False, indent=2))
