"""QWERTY-only synthetic keys, native observations and source-group holdouts.

Canonical readings seed NEW keys. Old mixed-kana errors are never reverse-
romanized as alleged observed keys; flick corruptions are never replayed.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import random
import re

from audit_corrector_benchmark import distance
from build_origin_corpus import romanize
from dataset_isolation import evaluation_keys, load_jsonl, normalize, sha256, source_keys, validate_rows
from evaluate_v4_repair_comparison import ReadingVerifier
from generate_typo_dataset import hira_to_kata
from keyboard_hybrid import NEIGHBORS
from native_qwerty import NativeQwerty


def pending(reading):
    match = re.search(r"[a-zA-Z]+$", reading)
    return match.group() if match else ""


def orthographic_alternatives(surface):
    # Authored okurigana pairs only; homophones are never interchangeable.
    pairs = [("貸出し", "貸し出し"), ("申込み", "申し込み"), ("振込み", "振り込み"),
             ("受取り", "受け取り"), ("取扱い", "取り扱い"), ("切替え", "切り替え"),
             ("書込み", "書き込み")]
    return sorted({surface.replace(a, b) for pair in pairs for a, b in (pair, pair[::-1]) if a in surface} - {surface})


def mutations(raw, rng):
    """Exactly one recorded physical-key operation per synthetic typo."""
    options = defaultdict(list)
    for i, key in enumerate(raw):
        for new in NEIGHBORS.get(key, ""):
            options["adjacent"].append((raw[:i] + new + raw[i+1:], i, key, new))
        options["deletion"].append((raw[:i] + raw[i+1:], i, key, ""))
        if key in "aeiou":
            options["missing_vowel"].append((raw[:i] + raw[i+1:], i, key, ""))
        options["duplication"].append((raw[:i] + key + raw[i:], i, "", key))
        if i+1 < len(raw) and raw[i] != raw[i+1]:
            options["transposition"].append((raw[:i] + raw[i+1] + key + raw[i+2:], i, raw[i:i+2], raw[i:i+2][::-1]))
    for kind in sorted(options):
        items = options[kind]
        for keys, position, before, after in rng.sample(items, min(2, len(items))):
            if keys:
                yield keys, kind, {"position": position, "before": before, "after": after}


def split_name(original, group):
    if original == "train":
        return "train"
    return "val" if int(hashlib.sha256(("qwerty-holdout:" + group).encode()).hexdigest()[:8], 16) % 2 == 0 else "test"


def build(source, output):
    if output.exists():
        raise FileExistsError(output)
    reserved = evaluation_keys()
    parts = {s: load_jsonl(source / f"{s}.jsonl") for s in ("train", "val")}
    validate_rows(parts["train"], parts["val"], reserved)
    native = NativeQwerty()
    verifier = ReadingVerifier(Path("ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin"))
    seeds, counts = {}, Counter()
    for original, rows in parts.items():
        for row in rows:
            reading = normalize(row.get("target_reading", ""))
            if not 3 <= len(reading) <= 32 or not all("ぁ" <= c <= "ゖ" or c == "ー" for c in reading):
                continue
            ctx = row["prompt"].split("\uee00", 1)[0].removeprefix("\uee02")
            surface = row["completion"]
            key = row["source_group"], ctx, reading, surface
            if key not in seeds:
                seeds[key] = original
    selected = {s: [] for s in ("train", "val", "test")}
    per_group = Counter()
    for index, ((group, ctx, reading, surface), original) in enumerate(sorted(seeds.items())):
        if per_group[group] >= 3:
            continue
        raw = romanize(reading)
        if not raw or len(raw) > 96 or native.decode(raw) != reading:
            counts["canonical_native_roundtrip_excluded"] += 1
            continue
        if not verifier.matches(surface, reading):
            counts["surface_reading_not_dictionary_verified"] += 1
            continue
        per_group[group] += 1
        split = split_name(original, group)
        seed_key = hashlib.sha256((group + ctx + reading + surface).encode()).hexdigest()
        rng = random.Random(seed_key)
        variants = [(raw, "clean", None)]
        for old, new in (("shi", "si"), ("chi", "ti"), ("tsu", "tu"), ("nn", "n")):
            alternative = raw.replace(old, new, 1)
            if alternative != raw and native.decode(alternative) == reading:
                variants.append((alternative, "valid_spelling", None))
        # Keep completed deletion-prefixes; never teach full-phrase completion.
        for cut in range(len(raw)//2, 2, -1):
            if native.decode(raw[:cut]) and not pending(native.decode(raw[:cut])):
                variants.append((raw[:cut], "editing_prefix", {"truncate_at": cut}))
                break
        variants += [(raw + "k", "pending", None), (raw + "n", "pending", None)]
        variants += list(mutations(raw, rng))
        seen = set()
        for keys, kind, edit in variants:
            observed = native.decode(keys)
            hold = bool(pending(observed))
            identity = (ctx, observed)
            if not observed or identity in seen:
                continue
            seen.add(identity)
            keep = hold or kind == "editing_prefix" or observed == reading
            answer = observed if hold or kind == "editing_prefix" else surface
            reference = observed if hold or kind == "editing_prefix" else reading
            row = {"id": seed_key[:16] + ":" + str(len(seen)), "source_group": group,
                   "context": ctx, "left_context": ctx, "input_reading": hira_to_kata(observed),
                   "target_reading": reference, "reference_surface": answer, "expected": answer,
                   "completion": answer, "prompt": ("\uee02" + ctx if ctx else "") + "\uee00" + hira_to_kata(observed) + "\uee01",
                   "canonical_reading": reading, "canonical_surface": surface,
                   "canonical_keys": raw, "observed_keys": keys, "key_edit": edit,
                   "pending": pending(observed), "expected_action": "wait" if hold else "keep" if keep else "repair",
                   "is_clean": keep, "typo_type": kind, "input_method": "qwerty",
                   "native_observed": observed, "reading_distance": distance(observed, reference),
                   "provenance": "New synthetic key edit from a canonical reading; native DLL replay; not a user trace"}
            row["acceptable"] = [s for s in orthographic_alternatives(answer) if verifier.matches(s, reference)]
            if source_keys(row) & reserved:
                counts["generated_row_touching_legacy_evaluation"] += 1
                continue
            selected[split].append(row)
        if index % 200 == 0:
            print(f"seed {index}/{len(seeds)} rows {sum(map(len, selected.values()))}", flush=True)
    # Exact observation contradictions are omitted, never resolved by choosing a
    # convenient target. Filter all related groups when new observations collide.
    observations = defaultdict(set)
    for rows in selected.values():
        for row in rows:
            observations[(row["context"], row["native_observed"])].add((row["completion"], row["expected_action"]))
    conflicts = {key for key, answers in observations.items() if len(answers) > 1}
    selected = {s: [r for r in rows if (r["context"], r["native_observed"]) not in conflicts] for s, rows in selected.items()}
    keys = {s: set().union(*(source_keys(r) for r in rows)) for s, rows in selected.items()}
    blocked = {r["source_group"] for r in selected["train"] if source_keys(r) & (keys["val"] | keys["test"])}
    blocked |= {r["source_group"] for r in selected["val"] if source_keys(r) & keys["test"]}
    selected = {s: [r for r in rows if r["source_group"] not in blocked] for s, rows in selected.items()}
    validate_rows(selected["train"], selected["val"] + selected["test"], reserved)
    validate_rows(selected["val"], selected["test"], reserved)
    output.mkdir(parents=True)
    manifest = {"schema": "native-qwerty-corrector-v1", "native": native.identity,
        "sources": {str(source / f"{s}.jsonl"): sha256(source / f"{s}.jsonl") for s in parts},
        "dictionary_sha256": sha256("ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin"),
        "code_sha256": sha256(__file__), "counts": dict(counts), "conflicting_observations": len(conflicts),
        "blocked_collision_groups": len(blocked), "outputs": {},
        "limits": ["Synthetic QWERTY errors, not empirical frequencies", "Prior development source groups reused; not an untouched historical test", "Surface labels dictionary-reading verified, not semantic human adjudication", "Canonical groups split before new key mutations"]}
    for split, rows in selected.items():
        path = output / f"{split}.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        manifest["outputs"][split] = {"rows": len(rows), "groups": len({r["source_group"] for r in rows}),
            "actions": dict(Counter(r["expected_action"] for r in rows)), "sha256": sha256(path)}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    native.clear()
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, default=Path("artifacts/datasets/corrector-v4-surface-20261030"))
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    print(json.dumps(build(a.source, a.out), ensure_ascii=False, indent=2))
