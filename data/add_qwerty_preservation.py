"""Add normal-only replay/canaries from matching source groups, never old errors."""
import argparse
from collections import Counter
import json
from pathlib import Path

from build_corrector_v4_dataset import is_clean
from dataset_isolation import evaluation_keys, load_jsonl, sha256, source_keys, validate_rows


def build(parent, source, output):
    if output.exists():
        raise FileExistsError(output)
    parts = {s: load_jsonl(parent / f"{s}.jsonl") for s in ("train", "val", "test")}
    groups = {s: {r["source_group"] for r in rows} for s, rows in parts.items()}
    counts = Counter()
    for split in ("train", "val"):
        seen = {(r["context"], r["input_reading"]) for r in parts[split]}
        for i, row in enumerate(load_jsonl(source / f"{split}.jsonl")):
            if row["source_group"] not in groups[split] or not is_clean(row):
                continue
            observed = row["prompt"].split("\uee00", 1)[1].removesuffix("\uee01")
            context = row["prompt"].split("\uee00", 1)[0].removeprefix("\uee02")
            if (context, observed) in seen:
                continue
            seen.add((context, observed))
            parts[split].append({**row, "id": f"preserve-{split}-{i}", "context": context,
                "native_observed": observed, "input_reading": observed,
                "expected": row["completion"], "expected_action": "keep", "is_clean": True,
                "acceptable": [], "pending": "", "preservation_canary": True,
                "input_method": "qwerty-normal-replay", "typo_type": "normal-preservation",
                "observed_keys": None, "canonical_keys": None,
                "provenance": "Existing normal observation; raw unavailable; no old synthetic typo reused"})
            counts[split] += 1
    # Retain original source groups; drop train groups touching either holdout.
    heldout = set().union(*(source_keys(r) for s in ("val", "test") for r in parts[s]))
    blocked = {r["source_group"] for r in parts["train"] if source_keys(r) & heldout}
    parts["train"] = [r for r in parts["train"] if r["source_group"] not in blocked]
    validate_rows(parts["train"], parts["val"] + parts["test"], evaluation_keys())
    validate_rows(parts["val"], parts["test"], evaluation_keys())
    output.mkdir(parents=True)
    manifest = json.loads((parent / "manifest.json").read_text(encoding="utf-8"))
    manifest.update(parent=str(parent), parent_manifest_sha256=sha256(parent / "manifest.json"),
                    normal_replay_sources={str(source/f"{s}.jsonl"):sha256(source/f"{s}.jsonl") for s in ("train","val")},
                    operation="normal-only replay and validation canaries from matching groups; no flick errors", added_normal=dict(counts),
                    preservation_code_sha256=sha256(__file__), outputs={})
    for split, rows in parts.items():
        path = output / f"{split}.jsonl"
        path.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows),encoding="utf-8")
        manifest["outputs"][split] = {"sha256":sha256(path),"rows":len(rows),"groups":len({r['source_group'] for r in rows}),
            "actions":dict(Counter(r['expected_action'] for r in rows))}
    (output/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return manifest


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent',type=Path,default=Path('artifacts/datasets/corrector-qwerty-native-20261030-r2'))
    p.add_argument('--source',type=Path,default=Path('artifacts/datasets/corrector-v4-surface-20261030'))
    p.add_argument('--out',type=Path,required=True)
    a=p.parse_args()
    print(json.dumps(build(a.parent,a.source,a.out),ensure_ascii=False,indent=2))
