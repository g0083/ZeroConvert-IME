"""Dependency-free split policy shared by dataset builders and training guards.

Equal input, canonical reading, or answer links rows into one source group.
This deliberately conservative policy ignores context when reserving evaluation.
"""

import hashlib
import json
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVALUATION_FILES = (
    ROOT / "data/kana_kanji_llm_testset_5x.jsonl",
    ROOT / "data/kana_kanji_hard_context_testset.jsonl",
    ROOT / "data/kana_kanji_ime_realistic_testset.jsonl",
    ROOT / "data/kana_kanji_colloquial_chat_testset.jsonl",
    ROOT / "data/typo_benchmark_300.jsonl",
    ROOT / "benchmarks/research/typo_testset.jsonl",
    ROOT / "benchmarks/research/predictive_testset.jsonl",
)
POLICY = "source-components-nfkc-kana-v1"


def normalize(text):
    text = unicodedata.normalize("NFKC", text or "")
    return "".join(
        chr(ord(c) - 96) if "ァ" <= c <= "ヶ" else c
        for c in text if not c.isspace()
    )


def load_jsonl(path):
    with Path(path).open(encoding="utf-8-sig") as source:
        return [json.loads(line) for line in source if line.strip()]


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_keys(row):
    """Unprefixed text keys also detect kana-only answer vs reading collisions."""
    fields = (
        "target_reading", "correct_kata", "target", "target_kata",
        "input_reading", "reading", "kata", "raw_input", "partial_input",
        "expected", "completion", "expected_continuation", "full_sentence",
    )
    texts = [row.get(field) for field in fields]
    for field in ("acceptable", "acceptable_continuations"):
        texts.extend(row.get(field) or [])
    prompt = row.get("prompt", "")
    if "\uee00" in prompt:
        texts.append(prompt.split("\uee00", 1)[1].split("\uee01", 1)[0])
    return {normalize(s) for s in texts if isinstance(s, str) and normalize(s)}


def evaluation_keys(paths=EVALUATION_FILES):
    keys = set()
    for path in paths:
        rows = load_jsonl(path)  # Missing/invalid evaluation is an error, never skip.
        if not rows:
            raise ValueError(f"Empty evaluation set: {path}")
        for row in rows:
            row_keys = source_keys(row)
            if not row_keys:
                raise ValueError(f"Evaluation row has no supported text identity: {path}")
            keys.update(row_keys)
    return keys


def separated_rows(rows, reserved, val_fraction=0.1, seed="20261027"):
    """Connect before filtering/splitting, so context/typo siblings cannot leak."""
    if not 0 < val_fraction < 1:
        raise ValueError("val_fraction must be between zero and one")
    parents = list(range(len(rows)))

    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    owners = {}
    keys = [source_keys(row) for row in rows]
    for i, row_keys in enumerate(keys):
        if not row_keys:
            raise ValueError(f"Row {i} has no source identity")
        for key in row_keys:
            if key in owners:
                parents[find(i)] = find(owners[key])
            else:
                owners[key] = i
    groups = {}
    for i in range(len(rows)):
        groups.setdefault(find(i), []).append(i)
    train, val, excluded = [], [], []
    for indices in groups.values():
        group_keys = set().union(*(keys[i] for i in indices))
        identity = hashlib.sha256("\n".join(sorted(group_keys)).encode()).hexdigest()
        blocked = bool(group_keys & reserved) or any(
            rows[i].get("category") == "testset_clean" for i in indices
        )
        bucket = int(hashlib.sha256(f"{seed}:{identity}".encode()).hexdigest(), 16) / 2**256
        destination = excluded if blocked else val if bucket < val_fraction else train
        for i in indices:
            destination.append({**rows[i], "source_group": identity})
    return train, val, excluded


def validate_rows(train, val, reserved):
    if not train or not val:
        raise ValueError("Both train and validation must contain rows")
    for row in train + val:
        if not row.get("prompt") or not row.get("completion"):
            raise ValueError("Training rows require prompt and completion")
    def all_keys(rows):
        return set().union(*(source_keys(r) for r in rows))
    train_keys, val_keys = all_keys(train), all_keys(val)
    counts = {
        "train_validation_shared_keys": len(train_keys & val_keys),
        "train_evaluation_shared_keys": len(train_keys & reserved),
        "validation_evaluation_shared_keys": len(val_keys & reserved),
        "evaluation_derived_rows": sum(r.get("category") == "testset_clean" for r in train + val),
        "train_validation_shared_groups": len(
            {r["source_group"] for r in train if r.get("source_group")}
            & {r["source_group"] for r in val if r.get("source_group")}
        ),
    }
    if any(counts.values()):
        raise ValueError(f"Dataset isolation failed: {counts}")
    return counts


def validate_training_files(train_path, val_path):
    """Run before loading weights, even when custom input paths are supplied."""
    return validate_rows(load_jsonl(train_path), load_jsonl(val_path), evaluation_keys())
