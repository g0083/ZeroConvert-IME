"""Add mixed kana/Latin keyboard noise and clean counterparts, then isolate again.

Uses only existing training/validation source groups, never benchmark answers.
The legacy Python romaji mapping is a synthetic noise generator, not an IME test.
"""
import argparse
import json
import random
from collections import Counter
from pathlib import Path

from dataset_isolation import ROOT, evaluation_keys, load_jsonl, normalize, separated_rows, sha256, validate_rows
from generate_typo_dataset import (
    hira_to_kata, hiragana_to_romaji, romaji_to_hiragana,
    mutate_adjacent_key, mutate_missing_vowel, mutate_transposition,
)


def augment(rows, seed=20261027):
    random.seed(seed)
    result = list(rows)
    seen = set()
    for row in rows:
        prompt = row["prompt"]
        context = prompt.split("\uee00", 1)[0].replace("\uee02", "")
        reading = normalize(row.get("target_reading") or row.get("target_kata") or "")
        # A mixed/kanji canonical target is not a verified kana reading.
        if not reading or not all("ぁ" <= c <= "ゖ" or c == "ー" for c in reading):
            continue
        identity = (context, reading, row["completion"])
        if identity in seen:
            continue
        seen.add(identity)
        raw = hiragana_to_romaji(reading)
        if not raw.isascii():
            continue
        inputs = [(reading, "clean_counterpart"), (row["completion"], "surface_preservation")]
        for name, mutate in (("adjacent", mutate_adjacent_key), ("missing_vowel", mutate_missing_vowel), ("transposition", mutate_transposition)):
            for _ in range(3):
                noisy = romaji_to_hiragana(mutate(raw))
                # Preserve floating letters instead of inventing a kana replacement.
                # Terminal unfinished consonants are not correction training targets.
                if noisy != reading and noisy and not (noisy[-1].isascii() and noisy[-1].isalpha()):
                    inputs.append((noisy, f"keyboard_{name}"))
        for text, kind in inputs:
            target = hira_to_kata(text)
            result.append({**row, "prompt": f"\uee02{context}\uee00{target}\uee01" if context else f"\uee00{target}\uee01",
                           "input_reading": target, "target_reading": reading,
                           "augmentation": kind})
    return result


def build(source, output):
    if output.exists():
        raise FileExistsError(f"Preserving existing dataset: {output}")
    paths = [source / "train.jsonl", source / "val.jsonl"]
    original = [r for p in paths for r in load_jsonl(p)]
    rows = augment(original)
    train, val, excluded = separated_rows(rows, evaluation_keys())
    def unique(rows):
        seen, result = set(), []
        for r in rows:
            key = r["prompt"], r["completion"]
            if key not in seen:
                seen.add(key)
                result.append(r)
        return result
    train, val = unique(train), unique(val)
    checks = validate_rows(train, val, evaluation_keys())
    output.mkdir(parents=True)
    outputs = {}
    for name, rows in (("train", train), ("val", val)):
        path = output / f"{name}.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        outputs[name] = {"sha256": sha256(path), "rows": len(rows),
                         "augmentation": dict(Counter(r.get("augmentation", "original") for r in rows))}
    manifest = {"sources": {str(p): sha256(p) for p in paths}, "seed": 20261027,
                "excluded_rows": len(excluded), "checks": checks, "outputs": outputs}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "artifacts/datasets/separated-v1/lora")
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts/datasets/corrector-v2-20261027")
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.out), ensure_ascii=False, indent=2))
