# -*- coding: utf-8 -*-
"""共通ベンチマーク正本 (canonical) を生成する。

生成元:
- conversion: data/kana_kanji_llm_testset_5x.jsonl (basic 230)
              data/kana_kanji_hard_context_testset.jsonl (hard 250)
              data/kana_kanji_ime_realistic_testset.jsonl (realistic 160)
              data/kana_kanji_colloquial_chat_testset.jsonl (colloquial 100)
              data/kana_kanji_additional_dict_testset.jsonl (additional_dict 100)
- typo: data/typo_benchmark_300.jsonl (legacy 300) + derived-clean 150
- predictive: benchmarks/research/predictive_testset.jsonl (30)

実行: python scripts/run.py benchmarks/common/build_common_benchmark.py
出力先: benchmarks/common/*.jsonl + manifest.json (+ parity_cases.jsonl は別途)
"""
from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = Path(__file__).resolve().parent

# typo_type -> 日本語ラベル (Android assets と一致させる)
TYPO_LABELS = {
    "missing_vowel": "母音抜け",
    "nasal": "撥音 (ん)",
    "adjacent": "QWERTY隣接キー",
    "prolonged": "長音 (ー)",
    "geminate": "促音 (っ)",
    "transposition": "順序入替 (転置)",
    "flick": "フリックミス",
    "clean": "正常入力 (FP検証)",
}


def load_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
        encoding="utf-8",
    )


def build_conversion() -> list[dict]:
    specs: list[tuple[str, str, str, str]] = [
        (
            "data/kana_kanji_llm_testset_5x.jsonl",
            "basic",
            "基本・文脈 (230問)",
            "data/kana_kanji_llm_testset_5x.jsonl",
        ),
        (
            "data/kana_kanji_hard_context_testset.jsonl",
            "hard",
            "高難度文脈 (250問)",
            "data/kana_kanji_hard_context_testset.jsonl",
        ),
        (
            "data/kana_kanji_ime_realistic_testset.jsonl",
            "realistic",
            "実IME (160問)",
            "data/kana_kanji_ime_realistic_testset.jsonl",
        ),
        (
            "data/kana_kanji_colloquial_chat_testset.jsonl",
            "colloquial",
            "口語 (100問)",
            "data/kana_kanji_colloquial_chat_testset.jsonl",
        ),
        (
            "data/kana_kanji_additional_dict_testset.jsonl",
            "additional_dict",
            "追加辞書 (100問)",
            "data/kana_kanji_additional_dict_testset.jsonl",
        ),
    ]
    rows: list[dict] = []
    next_id = 1
    for relpath, subset, label, origin in specs:
        source = load_jsonl(ROOT / relpath)
        for item in source:
            rows.append(
                {
                    "id": next_id,
                    "subset": subset,
                    "subset_label": label,
                    "category": item.get("category", ""),
                    "left_context": item.get("left_context", "なし"),
                    "target": item.get("target", ""),
                    "expected": item.get("expected", ""),
                    "acceptable": item.get("acceptable") or [],
                    "forbidden_examples": item.get("forbidden_examples") or [],
                    "focus": item.get("focus", ""),
                    "origin": origin,
                    "input_mode": "kana",
                    "expected_action": "convert",
                }
            )
            next_id += 1
    assert len(rows) == 840, len(rows)
    # 先頭 480 は従来通り basic 1..230, hard 231..480 になることを検査
    assert rows[0]["id"] == 1 and rows[229]["subset"] == "basic"
    assert rows[230]["id"] == 231 and rows[230]["subset"] == "hard"
    assert rows[479]["id"] == 480
    assert rows[480]["subset"] == "realistic"
    assert rows[640]["subset"] == "colloquial"
    assert rows[740]["subset"] == "additional_dict"
    return rows


def to_katakana(text: str) -> str:
    out = []
    for ch in text:
        if 0x3041 <= ord(ch) <= 0x3096:
            out.append(chr(ord(ch) + 0x60))
        else:
            out.append(ch)
    return "".join(out)


def input_mode_for(typo_type: str) -> str:
    if typo_type == "adjacent":
        return "qwerty"
    if typo_type == "flick":
        return "flick"
    return "kana"


def build_typo(conversion_rows: list[dict]) -> list[dict]:
    legacy = load_jsonl(ROOT / "data/typo_benchmark_300.jsonl")
    rows: list[dict] = []
    for item in legacy:
        typo_type = item.get("typo_type", "clean")
        is_clean = bool(item.get("is_clean", False))
        input_reading = item.get("input_reading", "")
        rows.append(
            {
                "id": int(item["id"]),
                "category": item.get("category", ""),
                "typo_type": typo_type,
                "typo_type_label": TYPO_LABELS.get(typo_type, typo_type),
                "is_clean": is_clean,
                "context": item.get("context", ""),
                "input_reading": input_reading,
                "target_reading": item.get("target_reading", ""),
                "expected": item.get("expected", ""),
                "acceptable": [],
                "raw_romaji": "",
                "raw_input": input_reading,
                "input_mode": input_mode_for(typo_type),
                "origin": "legacy300",
                "expected_action": "preserve" if is_clean else "repair",
                "source_subset": "",
            }
        )
    assert len(rows) == 300
    assert sum(1 for r in rows if r["is_clean"]) == 45

    # derived-clean: colloquial 100 問すべて + realistic 50 問 (カテゴリ横断)
    colloquial = [r for r in conversion_rows if r["subset"] == "colloquial"]
    realistic = [r for r in conversion_rows if r["subset"] == "realistic"]
    # realistic: カテゴリごとに id 順、各カテゴリから均等に (50問 -> 8カテゴリで各 6..7)
    by_cat: dict[str, list[dict]] = defaultdict(list)
    for r in realistic:
        by_cat[r["category"]].append(r)
    for items in by_cat.values():
        items.sort(key=lambda x: x["id"])
    realistic_picked: list[dict] = []
    # round-robin: カテゴリ順を固定 (辞書順) として均等に引く
    cats = sorted(by_cat.keys())
    idx = {c: 0 for c in cats}
    while len(realistic_picked) < 50:
        progressed = False
        for c in cats:
            if len(realistic_picked) >= 50:
                break
            if idx[c] < len(by_cat[c]):
                realistic_picked.append(by_cat[c][idx[c]])
                idx[c] += 1
                progressed = True
        assert progressed, "realistic が枯渇した"

    derived_sources = colloquial + realistic_picked
    assert len(derived_sources) == 150
    next_id = 301
    for conv in derived_sources:
        target_hira = conv["target"]
        input_kata = to_katakana(target_hira)
        rows.append(
            {
                "id": next_id,
                "category": conv["category"],
                "typo_type": "clean",
                "typo_type_label": TYPO_LABELS["clean"],
                "is_clean": True,
                "context": conv["left_context"] if conv["left_context"] != "なし" else "",
                "input_reading": input_kata,
                "target_reading": target_hira,
                "expected": conv["expected"],
                "acceptable": conv["acceptable"],
                "raw_romaji": "",
                "raw_input": input_kata,
                "input_mode": "kana",
                "origin": "derived-clean",
                "expected_action": "preserve",
                "source_subset": conv["subset"],
            }
        )
        next_id += 1
    assert len(rows) == 450
    assert sum(1 for r in rows if r["is_clean"]) == 195  # 45 + 150
    assert Counter(r["input_mode"] for r in rows)["qwerty"] == 60
    assert Counter(r["input_mode"] for r in rows)["flick"] == 40
    return rows


def build_predictive() -> list[dict]:
    source = load_jsonl(ROOT / "benchmarks/research/predictive_testset.jsonl")
    rows: list[dict] = []
    for item in source:
        rows.append(
            {
                "id": int(item["id"]),
                "category": item.get("category", ""),
                "context": item.get("context", ""),
                "partial_input": item.get("partial_input", ""),
                "expected_continuation": item.get("expected_continuation", ""),
                "acceptable_continuations": item.get("acceptable_continuations") or [],
                "full_sentence": item.get("full_sentence", ""),
                "origin": "benchmarks/research/predictive_testset.jsonl",
            }
        )
    assert len(rows) == 30
    return rows


def main() -> None:
    conversion = build_conversion()
    typo = build_typo(conversion)
    predictive = build_predictive()
    write_jsonl(OUT_DIR / "conversion.jsonl", conversion)
    write_jsonl(OUT_DIR / "typo.jsonl", typo)
    write_jsonl(OUT_DIR / "predictive.jsonl", predictive)

    manifest = {
        "schema": "zerocon-common-benchmark/v1",
        "conversion": {
            "count": len(conversion),
            "by_subset": dict(Counter(r["subset"] for r in conversion)),
        },
        "typo": {
            "count": len(typo),
            "clean": sum(1 for r in typo if r["is_clean"]),
            "repair": sum(1 for r in typo if not r["is_clean"]),
            "by_input_mode": dict(Counter(r["input_mode"] for r in typo)),
            "by_type": dict(Counter(r["typo_type"] for r in typo)),
        },
        "predictive": {"count": len(predictive)},
    }
    (OUT_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
