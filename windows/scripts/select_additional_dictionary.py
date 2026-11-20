"""Select and build an expansion of the current Windows trial dictionary."""

import argparse
import bz2
import gzip
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

import additional_dictionary as dictionary


GROUPS = ("common", "proper", "person")
TITLE_QUALIFIER = re.compile(r" \([^()]+\)$")


def article_title(title):
    return title.replace("_", " ").strip()


def article_documents(path, prefix_only=False):
    with bz2.open(path, "rt", encoding="utf-8") as source:
        while True:
            try:
                line = source.readline()
            except EOFError:
                if prefix_only:
                    break
                raise
            if not line or (prefix_only and not line.endswith("\n")):
                break
            row = json.loads(line)
            if "namespace" not in row and ("index" in row or "create" in row):
                continue
            if "namespace" not in row:
                raise ValueError(f"Unexpected CirrusSearch record in {path.name}")
            if row["namespace"] == 0 and row.get("text"):
                yield article_title(row["title"]), row["text"]


def parquet_documents(path):
    from pyarrow.parquet import ParquetFile

    for batch in ParquetFile(path).iter_batches(columns=["title", "text"], batch_size=256):
        for title, text in zip(batch.column(0).to_pylist(), batch.column(1).to_pylist(), strict=True):
            if title and text:
                yield article_title(title), text


def is_ascii_word(char):
    return char.isascii() and (char.isalnum() or char == "_")


def count_corpus(inputs, surfaces):
    import ahocorasick

    index = ahocorasick.Automaton(ahocorasick.STORE_LENGTH)
    for surface in surfaces:
        index.add_word(surface)
    index.make_automaton()
    documents, titles, pages = Counter(), set(), set()
    for source, path in inputs:
        print(f"Reading {path.name}", flush=True)
        if source["type"] == "titles":
            with gzip.open(path, "rt", encoding="utf-8") as table:
                for line in table:
                    title = article_title(line)
                    for form in (title, TITLE_QUALIFIER.sub("", title)):
                        if form in surfaces:
                            titles.add(form)
        elif source["type"] in ("cirrus", "cirrus-prefix", "wikipedia-parquet"):
            articles = (parquet_documents(path) if source["type"] == "wikipedia-parquet"
                        else article_documents(path, prefix_only=source["type"] == "cirrus-prefix"))
            for title, text in articles:
                if title in pages:
                    continue
                pages.add(title)
                matches = set()
                for end, length in index.iter_long(text):
                    start = end + 1 - length
                    if start and is_ascii_word(text[start]) and is_ascii_word(text[start - 1]):
                        continue
                    if end + 1 < len(text) and is_ascii_word(text[end]) and is_ascii_word(text[end + 1]):
                        continue
                    matches.add(text[start:end + 1])
                documents.update(matches)
            print(f"Counted {len(pages):,} distinct articles so far", flush=True)
        else:
            raise ValueError(f"Unknown corpus source type: {source['type']}")
    return documents, titles, len(pages)


def ranked_candidates(candidates, documents, titles, existing_readings):
    ranked = []
    for (reading, surface), (cost, pos, archive, group) in candidates.items():
        count = documents[surface]
        title = surface in titles
        if count < 2 and not title:
            continue
        existing = existing_readings[reading]
        priority = (count + 4 * title) / math.sqrt(1 + existing)
        ranked.append(((-priority, -count, cost, reading, surface), {
            "reading": reading, "surface": surface, "pos": pos, "group": group,
            "archive": archive, "sudachiCost": cost, "documents": count,
            "articleTitle": title, "existingSameReading": existing,
            "priority": round(priority, 6),
        }))
    ranked.sort(key=lambda item: item[0])
    return [row for _, row in ranked]


def choose_rows(rows, count, previous_reading_additions=None):
    regular = count * 2 // 5
    quotas = dict(zip(GROUPS, (regular, regular, count - 2 * regular), strict=True))
    group_counts, reading_counts = Counter(), Counter(previous_reading_additions or {})
    selected_surfaces = set()
    selected, deferred = [], []

    def add(row):
        reading = row["reading"]
        limit = 1 if row["existingSameReading"] >= 10 else 3
        if reading_counts[reading] >= limit or row["surface"] in selected_surfaces:
            return
        selected.append(row)
        selected_surfaces.add(row["surface"])
        reading_counts[reading] += 1
        group_counts[row["group"]] += 1

    for row in rows:
        if group_counts[row["group"]] >= quotas[row["group"]]:
            deferred.append(row)
        else:
            add(row)
    for row in deferred:
        if len(selected) >= count:
            break
        add(row)
    return selected, quotas, group_counts


def source_sections(path, descriptor):
    manifest = dictionary.read_json(path)
    if manifest["schemaVersion"] != 1:
        raise ValueError(f"Unsupported {descriptor} schema")
    return manifest


def choose(lock, previous, corpus, count, output):
    if output.exists():
        raise FileExistsError(output)
    if count <= 0:
        raise ValueError("Selection count must be positive")
    prev_manifest = previous / "additional-dictionary.json"
    orig = source_sections(lock, "source lock")
    corp = source_sections(corpus, "corpus manifest")
    previous_reading_additions = Counter(
        row["reading"] for row in dictionary.read_json(previous / "entries.json")
        if any(source.get("selection") == "document-frequency" for source in row["sources"])
    )
    previous_root = previous / "Dictionary/louds"
    baseline, groups = dictionary.standard_entries(previous_root)
    del groups
    existing_pairs = {(reading, row.surface) for reading, rows in baseline.items() for row in rows}
    existing_surfaces = {surface for _, surface in existing_pairs}
    existing_readings = Counter(reading for reading, _ in existing_pairs)
    del baseline
    archives = [dictionary.checked_source(lock.parent, row) for row in orig["sudachi"]]
    characters = set((previous_root / "charID.chid").read_text(encoding="utf-8"))
    excluded = Counter()
    raw = {}
    for key, fields, subtype in dictionary.sudachi_source_rows(archives, characters, existing_pairs, excluded):
        if key[1] in existing_surfaces:
            excluded["unconfirmedAlternativeReading"] += 1
            continue
        group = "person" if subtype == "人名" else ("proper" if fields[1] == "proper" else "common")
        choice = (*fields, group)
        old = raw.get(key)
        if old is None or choice < old:
            raw[key] = choice
    candidates = {word for _, word in raw}
    inputs = [(row, dictionary.checked_source(corpus.parent, dict(row, path=row["file"])))
              for row in corp["sources"]]
    print(f"Loaded {len(raw):,} candidate reading/surface pairs", flush=True)
    counts, titles, pages = count_corpus(inputs, candidates)
    ranked = ranked_candidates(raw, counts, titles, existing_readings)
    selected, quotas, group_counts = choose_rows(ranked, count, previous_reading_additions)
    if len(selected) != count:
        raise ValueError(f"Only {len(selected):,} of {count:,} requested entries passed the rules")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        report = {
            "schemaVersion": 1, "baselineManifestSha256": dictionary.digest(prev_manifest),
            "sudachiSourceLockSha256": dictionary.digest(lock), "corpus": corp["sources"],
            "articles": pages, "candidatePairs": len(raw), "attestedPairs": len(ranked),
            "target": count, "quotas": quotas, "selectedCounts": group_counts,
            "excludedSudachiRows": excluded, "entries": selected,
        }
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"Selected {count:,} entries: {dict(group_counts)}", flush=True)


def extend(previous, selection, builder, output):
    if output.exists():
        raise FileExistsError(output)
    recipe = source_sections(selection, "selection")
    source = previous / "Dictionary"
    manifest_file = previous / "additional-dictionary.json"
    if dictionary.digest(manifest_file) != recipe["baselineManifestSha256"]:
        raise ValueError("Selection was made against a different baseline dictionary")
    metadata = source_sections(manifest_file, "previous dictionary manifest")
    if metadata["files"] != dictionary.file_entries(source):
        raise ValueError("Baseline dictionary files differ from their manifest")
    baseline, groups = dictionary.standard_entries(source / "louds")
    characters = set((source / "louds/charID.chid").read_text(encoding="utf-8"))
    records = dictionary.read_json(previous / "entries.json")
    if len(records) != metadata["counts"]["total"] or len(recipe["entries"]) != recipe["target"]:
        raise ValueError("Unexpected selection or baseline entry count")
    picked = {}
    for row in recipe["entries"]:
        key = (row["reading"], row["surface"])
        if (key in picked or any(entry.surface == key[1] for entry in baseline.get(key[0], []))
                or not dictionary.valid_word(*key, characters, Counter())):
            raise ValueError(f"Invalid or already registered extension entry: {key}")
        picked[key] = (row["pos"], [{
            "source": "sudachi", "archive": row["archive"], "selection": "document-frequency",
            "group": row["group"], "documents": row["documents"], "articleTitle": row["articleTitle"],
        }])
    additions = list(dictionary.make_entries(picked, baseline))
    metadata.pop("files")
    metadata["counts"]["total"] = len(additions) + len(records)
    cumulative = Counter(metadata["counts"].get("corpusSelection", {}))
    cumulative.update(recipe["selectedCounts"])
    metadata["counts"]["corpusSelection"] = cumulative
    if prior_selection := metadata["sources"].get("corpusSelection"):
        metadata["sources"].setdefault("previousCorpusSelections", []).append(prior_selection)
    metadata["sources"]["corpusSelection"] = {
        "sha256": dictionary.digest(selection), "baselineManifestSha256": recipe["baselineManifestSha256"],
        "articles": recipe["articles"], "inputs": recipe["corpus"], "selectedCounts": recipe["selectedCounts"],
    }
    dictionary.publish_dictionary(source, builder, output, groups, additions, metadata, records)
    print(f"Generated {len(additions):,} new entries in {output}", flush=True)


def candidate_case(group, case_id, reading, surface):
    hira = "".join(chr(ord(ch) - 0x60) if "ァ" <= ch <= "ヶ" else ch for ch in reading)
    return f"{group}\t{case_id}\tkana\t{hira}についてしらべます\t{surface}について調べます"


def collision_cases(report, previous):
    manifest = previous / "additional-dictionary.json"
    if dictionary.digest(manifest) != report["baselineManifestSha256"]:
        raise ValueError("Collision tests must use the selection's baseline dictionary")
    baseline, _ = dictionary.standard_entries(previous / "Dictionary/louds")
    readings = {row["reading"] for row in report["entries"] if row["existingSameReading"] >= 3}
    if len(readings) < 30:
        raise ValueError("Too few affected readings for 30 collision cases")
    ranked = sorted(readings, key=lambda reading: (
        -len({row.surface for row in baseline[reading]}),
        hashlib.sha256(reading.encode()).digest(),
    ))
    cases = []
    for index, reading in enumerate(ranked[:30], 1):
        word = max(baseline[reading], key=lambda row: (row.score, row.surface)).surface
        cases.append(candidate_case("collision", f"collision-{index:02}", reading, word))
    return cases


def make_cases(selection, control, output, previous=None):
    report = source_sections(selection, "selection")
    by_group = defaultdict(list)
    for row in report["entries"]:
        by_group[row["group"]].append(row)
    cases = []
    for group in GROUPS:
        ordered = sorted(by_group[group], key=lambda row: (-row["priority"], row["reading"], row["surface"]))
        for band in range(3):
            examples = ordered[len(ordered) * band // 3:len(ordered) * (band + 1) // 3]
            if len(examples) < 20:
                raise ValueError(f"Too few entries in {group} band {band + 1}")
            examples.sort(key=lambda row: hashlib.sha256((row["reading"] + "\t" + row["surface"]).encode()).digest())
            for index, row in enumerate(examples[:20], 1):
                case_id = f"bulk-{group}-{band + 1}-{index:02}"
                cases.append(candidate_case("bulk", case_id, row["reading"], row["surface"]))
    if previous is not None:
        cases.extend(collision_cases(report, previous))
    original = control.read_text(encoding="utf-8").splitlines()
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join([*original, *cases]) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    select = commands.add_parser("select")
    select.add_argument("--source-lock", required=True, type=Path)
    select.add_argument("--previous", required=True, type=Path)
    select.add_argument("--corpus", required=True, type=Path)
    select.add_argument("--count", type=int, default=50_000)
    select.add_argument("--output", required=True, type=Path)
    build = commands.add_parser("extend")
    build.add_argument("--previous", required=True, type=Path)
    build.add_argument("--selection", required=True, type=Path)
    build.add_argument("--builder", required=True, type=Path)
    build.add_argument("--output", required=True, type=Path)
    cases = commands.add_parser("cases")
    cases.add_argument("--selection", required=True, type=Path)
    cases.add_argument("--control", required=True, type=Path)
    cases.add_argument("--output", required=True, type=Path)
    cases.add_argument("--previous", type=Path, help="Adds 30 homophone cases against this dictionary")
    args = parser.parse_args()
    if args.command == "select":
        choose(args.source_lock, args.previous, args.corpus, args.count, args.output)
    elif args.command == "extend":
        extend(args.previous, args.selection, args.builder, args.output)
    else:
        make_cases(args.selection, args.control, args.output, args.previous)


if __name__ == "__main__":
    main()
