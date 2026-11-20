"""Build an offline, opt-in Windows trial dictionary from frozen public inputs."""

import argparse
import csv
import hashlib
import io
import json
import math
import os
import re
import shutil
import statistics
import struct
import subprocess
import tempfile
import unicodedata
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import NamedTuple


WINDOWS = Path(__file__).resolve().parent.parent
BASE = WINDOWS / "server-swift/azooKey_dictionary_storage/Dictionary"
ARCHIVES = ("small_lex", "core_lex", "notcore_lex")
NOTICES = ("LEGAL", "LICENSE-2.0.txt")
LIMITS = {"proper": 3000, "common": 1000, "sahen": 1000}
WIKIDATA_LIMIT = 100
MAX_READING = 20  # DicdataStore.maxlength
POS = {"proper": 1288, "common": 1285, "sahen": 1283}
GENERAL_MID = 501
SUDACHI_BUCKET = "https://sudachi.s3.ap-northeast-1.amazonaws.com/sudachidict-raw"
WIKIDATA_ENTITY = "https://www.wikidata.org/wiki/Special:EntityData"
QID = re.compile(r"Q[1-9][0-9]*\Z")
ROW = struct.Struct("<HHHf")
U16 = struct.Struct("<H")
U32 = struct.Struct("<I")
AZR2_ROW = struct.Struct("<HHf")


class Entry(NamedTuple):
    reading: str
    surface: str
    lcid: int
    rcid: int
    mid: int
    score: float


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8", newline="\n")


def selection(path):
    records = read_json(path)
    if not isinstance(records, list) or not records:
        raise ValueError("Wikidata selection must be a nonempty JSON array")
    seen = set()
    for record in records:
        qid = record["qid"]
        if not QID.fullmatch(qid) or qid in seen:
            raise ValueError(f"Invalid or repeated Wikidata QID: {qid}")
        seen.add(qid)
    return records


def freeze_inputs(sudachi, version, wikidata, chosen, output):
    if not re.fullmatch(r"[0-9]{8}", version):
        raise ValueError("Sudachi version must have eight digits")
    if output.exists():
        raise FileExistsError(output)

    def source(path, url):
        return {"path": os.path.relpath(path.resolve(), output.parent.resolve()).replace(os.sep, "/"),
                "sha256": digest(path), "url": url}

    manifest = {
        "schemaVersion": 1,
        "sudachiVersion": version,
        "sudachi": [source(sudachi / f"{name}.zip", f"{SUDACHI_BUCKET}/{version}/{name}.zip")
                    for name in ARCHIVES],
        "notices": [source(sudachi / name,
                           f"https://github.com/WorksApplications/SudachiDict/blob/v{version}/{name}")
                    for name in NOTICES],
        "wikidataSelection": source(chosen, None),
        "wikidata": {
            record["qid"]: source(wikidata / f"{record['qid']}.json",
                                  f"{WIKIDATA_ENTITY}/{record['qid']}.json")
            for record in selection(chosen)
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def checked_source(root, record):
    path = root / record["path"]
    if digest(path) != record["sha256"]:
        raise ValueError(f"Source hash mismatch: {path}")
    return path


def read_louds(path):
    """Read all six forward-dictionary fields, retaining the order inside each slot."""
    data = path.read_bytes()
    slots, = U16.unpack_from(data)
    header = 2 + slots * U32.size
    offsets = [U32.unpack_from(data, 2 + index * U32.size)[0] for index in range(slots)]
    for start, end in zip(offsets, offsets[1:] + [len(data)]):
        if not header <= start <= end - U16.size or end > len(data):
            raise ValueError(f"Invalid LOUDS slot: {path}")
        count, = U16.unpack_from(data, start)
        if not count:
            continue
        row_start = start + U16.size
        text_start = row_start + count * ROW.size
        if text_start >= end:
            raise ValueError(f"Truncated LOUDS entry: {path}")
        reading, *surfaces = data[text_start:end].decode("utf-8").split("\t")
        if not reading or len(surfaces) != count:
            raise ValueError(f"Invalid LOUDS text: {path}")
        for index, surface in enumerate(surfaces):
            lcid, rcid, mid, score = ROW.unpack_from(data, row_start + index * ROW.size)
            if not math.isfinite(score) or score > 0:
                raise ValueError(f"Unsupported LOUDS score: {path}")
            yield Entry(reading, surface or reading, lcid, rcid, mid, score)


def standard_entries(louds):
    groups = defaultdict(list)
    entries = defaultdict(list)
    for path in sorted(louds.glob("*.loudstxt3")):
        for row in read_louds(path):
            entries[row.reading].append(row)
    if not entries:
        raise ValueError(f"No entries in standard dictionary: {louds}")
    for reading, rows in entries.items():
        groups[reading[0]].extend(rows)
    return entries, groups


def katakana(value):
    text = unicodedata.normalize("NFKC", value).strip()
    return "".join(chr(ord(char) + 0x60) if "ぁ" <= char <= "ゖ" else char for char in text)


def valid_word(reading, surface, characters, excluded):
    if not reading or not surface:
        excluded["empty"] += 1
    elif len(reading) > MAX_READING:
        excluded["longReading"] += 1
    elif any(char not in characters or not ("ァ" <= char <= "ヺ" or char in "ー・ヽヾ")
             for char in reading):
        excluded["unsupportedReading"] += 1
    elif any(unicodedata.category(char) == "Cc" for char in surface):
        excluded["unsupportedSurface"] += 1
    else:
        return True
    return False


def sudachi_pos(fields):
    if fields[5] != "名詞":
        return None
    if fields[6] == "固有名詞":
        return "proper"
    if fields[6] == "普通名詞":
        if fields[7] == "サ変可能":
            return "sahen"
        if fields[7] == "一般":
            return "common"
    return None


def sudachi_source_rows(archives, characters, existing, excluded):
    """CSV columns follow the official V0 source layout for this release."""
    for name, archive in zip(ARCHIVES, archives, strict=True):
        with zipfile.ZipFile(archive) as bundle:
            members = [member for member in bundle.namelist() if Path(member).name == f"{name}.csv"]
            if len(members) != 1:
                raise ValueError(f"Expected exactly one {name}.csv in {archive}")
            with io.TextIOWrapper(bundle.open(members[0]), encoding="utf-8-sig", newline="") as rows:
                for fields in csv.reader(rows, strict=True):
                    if len(fields) < 12:
                        raise ValueError(f"Malformed Sudachi CSV in {archive}")
                    pos = sudachi_pos(fields)
                    if pos is None:
                        continue
                    cost = int(fields[3])
                    key = (katakana(fields[11]), fields[4].strip())
                    if not valid_word(*key, characters, excluded):
                        continue
                    if key in existing:
                        excluded["standardDuplicate"] += 1
                        continue
                    yield key, (cost, pos, name), fields[7]


def sudachi_words(archives, characters, existing, excluded):
    best = {}
    for key, candidate, _ in sudachi_source_rows(archives, characters, existing, excluded):
        old = best.get(key)
        if old is not None:
            excluded["sourceDuplicate"] += 1
        if old is None or candidate < old:
            best[key] = candidate
    return best


def wikidata_word(entity, record):
    """A reviewed reading needs a primary source when Wikidata is ambiguous."""
    def name_reading(value):
        # P1814 commonly separates family and given names with a space.
        return katakana("".join(value.split()))

    label = entity.get("labels", {}).get("ja", {}).get("value")
    aliases = {alias["value"] for alias in entity.get("aliases", {}).get("ja", [])}
    names = aliases | {label} if label else aliases
    mappings = set()
    unqualified = set()
    for statement in entity.get("claims", {}).get("P1814", []):
        value = statement["mainsnak"].get("datavalue", {}).get("value")
        if statement.get("rank") == "deprecated" or not isinstance(value, str):
            continue
        qualifiers = statement.get("qualifiers", {}).get("P5168", [])
        if qualifiers:
            for qualifier in qualifiers:
                name = qualifier.get("datavalue", {}).get("value", {})
                if isinstance(name, dict) and name.get("language") == "ja" and name.get("text") in names:
                    mappings.add((name_reading(value), name["text"]))
        else:
            unqualified.add(name_reading(value))

    if record.get("evidence") and (record.get("surface"), record.get("reading")):
        if record.get("surface") in names and record.get("reading"):
            return name_reading(record["reading"]), record["surface"]
    if not mappings and len(names) == 1 and len(unqualified) == 1 and label:
        return next(iter(unqualified)), label
    if len(mappings) == 1:
        return next(iter(mappings))
    return None


def wikidata_words(records, paths, characters, existing, excluded):
    words = defaultdict(list)
    for record in records:
        qid = record["qid"]
        entity = read_json(paths[qid])["entities"][qid]
        revision = entity.get("lastrevid")
        if entity.get("id") != qid or not isinstance(revision, int) or revision <= 0:
            raise ValueError(f"Invalid Wikidata entity or revision: {qid}")
        key = wikidata_word(entity, record)
        if key is None:
            excluded["ambiguousOrMissingReading"] += 1
        elif valid_word(*key, characters, excluded):
            if key in existing:
                excluded["standardDuplicate"] += 1
            elif key in words or len(words) < WIKIDATA_LIMIT:
                provenance = {"source": "wikidata", "qid": qid, "revision": revision}
                if record.get("evidence"):
                    provenance["evidence"] = record["evidence"]
                words[key].append(provenance)
            else:
                excluded["limit"] += 1
    return words


def choose_words(sudachi, wikidata):
    picked = {}
    counts = Counter()
    for key in sorted(sudachi, key=lambda pair: (sudachi[pair][0], *pair)):
        _, pos, archive = sudachi[key]
        if counts[pos] < LIMITS[pos] or key in wikidata:
            if counts[pos] < LIMITS[pos]:
                counts[pos] += 1
                picked[key] = (pos, [{"source": "sudachi", "archive": archive}])
            else:
                # A Wikidata selection still has Sudachi POS and provenance past the Sudachi quota.
                picked[key] = (pos, [{"source": "sudachi", "archive": archive, "outsideQuota": True}])
    for key, provenance in wikidata.items():
        pos, sources = picked.get(key, ("proper", []))
        picked[key] = (pos, sources + provenance)
    return picked, counts


def make_entries(picked, baseline):
    scores = defaultdict(list)
    for entries in baseline.values():
        for row in entries:
            if row.lcid == row.rcid:
                scores[row.lcid].append(row.score)
    medians = {cid: statistics.median(scores[cid]) for cid in POS.values()}
    for key in sorted(picked):
        reading, surface = key
        pos, provenance = picked[key]
        cid = POS[pos]
        score = medians[cid] - 1
        if reading in baseline:
            score = min(score, max(row.score for row in baseline[reading]) - 1)
        score = struct.unpack("<f", struct.pack("<f", score))[0]
        yield Entry(reading, surface, cid, cid, GENERAL_MID, score), provenance


def identifier(character):
    raw = character.encode("utf-16-le")
    return "[" + "_".join(f"{value:04X}" for value, in struct.iter_unpack("<H", raw)) + "]"


def part_suffix(name, prefix):
    if not name.startswith(prefix):
        return None
    suffix = name[len(prefix):]
    if suffix in (".louds", ".loudschars2") or re.fullmatch(r"\d+\.loudstxt3", suffix):
        return suffix
    return None


def rebuild_parts(builder, output_louds, rows, additions):
    updated = defaultdict(list)
    for row, _ in additions:
        updated[row.reading[0]].append(row)
    if not updated:
        raise ValueError("No additional entries were selected")
    with tempfile.TemporaryDirectory(prefix="builder-", dir=output_louds.parent.parent) as temporary:
        work = Path(temporary)
        source = work / "entries.tsv"
        with source.open("w", encoding="utf-8", newline="\n") as handle:
            for char, added in updated.items():
                for row in [*rows[char], *added]:
                    handle.write("\t".join(map(str, row)) + "\n")
        subprocess.run([builder, source, work, output_louds / "charID.chid"], check=True)

        for char, added in updated.items():
            expected = defaultdict(list)
            for row in [*rows[char], *added]:
                expected[row.reading].append(row)
            escaped = identifier(char)
            compiled = {suffix: path for path in work.iterdir()
                        if (suffix := part_suffix(path.name, escaped))}
            actual = defaultdict(list)
            for suffix, path in sorted(compiled.items()):
                if suffix.endswith(".loudstxt3"):
                    for row in read_louds(path):
                        actual[row.reading].append(row)
            if not {".louds", ".loudschars2"}.issubset(compiled) or actual != expected:
                raise ValueError(f"Rebuilt LOUDS changed entries or ordering for {char}")

            use_legacy = ((output_louds / f"{char}.louds").is_file()
                          and not (output_louds / f"{escaped}.louds").is_file())
            target = char if use_legacy else escaped
            for path in output_louds.iterdir():
                if part_suffix(path.name, char) or part_suffix(path.name, escaped):
                    path.unlink()
            for suffix, path in compiled.items():
                path.replace(output_louds / (target + suffix))


def verify_reverse(path, additions):
    expected = {(row.surface, row.reading) for row, _ in additions}
    data = path.read_bytes()
    if data[:4] != b"AZR2":
        raise ValueError("Invalid reverse dictionary header")
    count, = U32.unpack_from(data, 4)
    for index in range(count):
        start, = U32.unpack_from(data, 8 + index * U32.size)
        surface_len, reading_len, _ = AZR2_ROW.unpack_from(data, start)
        surface_start = start + AZR2_ROW.size
        reading_start = surface_start + surface_len
        surface = data[surface_start:reading_start].decode("utf-8")
        reading = data[reading_start:reading_start + reading_len].decode("utf-8")
        expected.discard((surface, reading))
    if expected:
        raise ValueError(f"Missing {len(expected)} additions in reverse dictionary")


def file_entries(root):
    return [{"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size,
             "sha256": digest(path)} for path in sorted(root.rglob("*")) if path.is_file()]


def publish_dictionary(source, builder, output, groups, additions, metadata, records, notices=()):
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".additional-dictionary-", dir=output.parent) as temporary:
        stage = Path(temporary) / "result"
        dictionary = stage / "Dictionary"
        shutil.copytree(source, dictionary)
        rebuild_parts(builder.resolve(strict=True), dictionary / "louds", groups, additions)
        reverse = dictionary / "Reverse/reverse-v2.bin"
        subprocess.run(["node", WINDOWS / "scripts/generate-reverse-dictionary.mjs",
                        "--source", dictionary / "louds", "--output", reverse], check=True)
        verify_reverse(reverse, additions)
        if notices:
            notice_dir = dictionary / "AdditionalDictionaryNotices"
            notice_dir.mkdir()
            for name, source_notice in zip(NOTICES, notices, strict=True):
                shutil.copyfile(source_notice, notice_dir / name)
            (notice_dir / "Wikidata.txt").write_text(
                "Wikidata structured data (CC0 1.0)\nhttps://www.wikidata.org/wiki/Wikidata:Licensing\n",
                encoding="utf-8", newline="\n")
        write_json(stage / "additional-dictionary.json", dict(metadata, files=file_entries(dictionary)))
        write_json(stage / "entries.json", [
            *records,
            *({"reading": row.reading, "surface": row.surface, "cid": row.lcid, "score": row.score,
               "sources": provenance} for row, provenance in additions)
        ])
        stage.rename(output)


def generate(lock, builder, output):
    if output.exists():
        raise FileExistsError(output)
    manifest = read_json(lock)
    if manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported source lock schema")
    sources = [checked_source(lock.parent, row) for row in manifest["sudachi"]]
    notices = [checked_source(lock.parent, row) for row in manifest["notices"]]
    chosen = selection(checked_source(lock.parent, manifest["wikidataSelection"]))
    wiki_paths = {qid: checked_source(lock.parent, row) for qid, row in manifest["wikidata"].items()}
    revision = subprocess.check_output(["git", "-C", BASE.parent, "rev-parse", "HEAD"], text=True).strip()
    character_ids = (BASE / "louds/charID.chid").read_text(encoding="utf-8")
    baseline, groups = standard_entries(BASE / "louds")
    existing = {(reading, row.surface) for reading, rows in baseline.items() for row in rows}
    rejected_sudachi, rejected_wikidata = Counter(), Counter()
    sudachi = sudachi_words(sources, set(character_ids), existing, rejected_sudachi)
    wikidata = wikidata_words(chosen, wiki_paths, set(character_ids), existing, rejected_wikidata)
    picked, counts = choose_words(sudachi, wikidata)
    if not counts or not wikidata:
        raise ValueError("Each source must contribute at least one additional trial word")
    additions = list(make_entries(picked, baseline))
    # Keep reproducible provenance without exposing workspace paths.
    input_sources = {name: {key: value for key, value in row.items() if key != "path"}
                     for name, row in [
                         *zip(ARCHIVES, manifest["sudachi"], strict=True),
                         *zip(NOTICES, manifest["notices"], strict=True),
                         ("wikidataSelection", manifest["wikidataSelection"]),
                         *manifest["wikidata"].items(),
                     ]}
    metadata = {
        "schemaVersion": 1, "kind": "additional-trial-dictionary", "baseRevision": revision,
        "sources": {"sudachiVersion": manifest["sudachiVersion"], "inputs": input_sources,
                    "licenses": {"sudachi": "AdditionalDictionaryNotices", "wikidata": "CC0-1.0"}},
        "counts": {"total": len(additions), "sudachi": counts, "wikidata": len(wikidata),
                   "excludedSudachi": dict(rejected_sudachi, limit=len(sudachi) - sum(counts.values())),
                   "excludedWikidata": rejected_wikidata},
    }
    publish_dictionary(BASE, builder, output, groups, additions, metadata, [], notices)
    print(f"Generated {len(additions)} additions in {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    freeze = commands.add_parser("freeze", help="Hash previously downloaded public inputs")
    freeze.add_argument("--sudachi-dir", type=Path, required=True)
    freeze.add_argument("--sudachi-version", required=True)
    freeze.add_argument("--wikidata-dir", type=Path, required=True)
    freeze.add_argument("--selection", type=Path, required=True)
    freeze.add_argument("--output", type=Path, required=True)
    build = commands.add_parser("generate", help="Build and verify an offline trial dictionary")
    build.add_argument("--source-lock", type=Path, required=True)
    build.add_argument("--builder", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "freeze":
        freeze_inputs(args.sudachi_dir, args.sudachi_version, args.wikidata_dir, args.selection, args.output)
    else:
        generate(args.source_lock, args.builder, args.output)


if __name__ == "__main__":
    main()
