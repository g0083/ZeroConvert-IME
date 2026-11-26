"""Build town, chome and koaza entries from the nationwide address register."""

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date
import io
from pathlib import Path
import re
import unicodedata
from zipfile import ZipFile

import additional_dictionary as dictionary
from add_municipalities import NOTICE


TOWN_URL = "https://data.address-br.digital.go.jp/mt_town/mt_town_all.csv.zip"
POSTAL_URL = "https://www.post.japanpost.jp/service/search/zipcode/download/utf/zip/utf_ken_all.zip"
FIELDS = ("oaza_cho", "chome", "koaza")
READING_CHARS = re.compile(r"[ァ-ヺー・ヽヾ0-9]+\Z")
PARENTHETICAL = re.compile(r"\([^()]*\)$")
DIGITS = ("ゼロ", "イチ", "ニ", "サン", "ヨン", "ゴ", "ロク", "ナナ", "ハチ", "キュウ")
HUNDREDS = ("", "ヒャク", "ニヒャク", "サンビャク", "ヨンヒャク", "ゴヒャク", "ロッピャク", "ナナヒャク", "ハッピャク", "キュウヒャク")
THOUSANDS = ("", "セン", "ニセン", "サンゼン", "ヨンセン", "ゴセン", "ロクセン", "ナナセン", "ハッセン", "キュウセン")
CHOME_ENDINGS = {"イチ": "イッ", "ハチ": "ハッ", "ジュウ": "ジュッ"}


def clean_reading(value):
    value = dictionary.katakana(value)
    # Some ABR readings use ASCII hyphens for the kana long-vowel mark.
    value = re.sub(r"(?<=[ァ-ヺ])-(?=[ァ-ヺ]|$)", "ー", value)
    return value if READING_CHARS.fullmatch(value) else ""


def town_rows(path, as_of):
    with ZipFile(path) as archive, io.TextIOWrapper(
        archive.open("mt_town_all.csv"), encoding="utf-8-sig", newline=""
    ) as source:
        for row in csv.DictReader(source):
            if row["efct_date"] <= as_of and (not row["ablt_date"] or row["ablt_date"] > as_of):
                yield row


def postal_readings(path):
    readings = defaultdict(set)
    with ZipFile(path) as archive, io.TextIOWrapper(
        archive.open("utf_ken_all.csv"), encoding="utf-8-sig", newline=""
    ) as source:
        for row in csv.reader(source):
            code, surface = row[0], unicodedata.normalize("NFKC", row[8])
            reading = unicodedata.normalize("NFKC", row[5])
            # Treat a trailing scope annotation as such only when both fields have it.
            if PARENTHETICAL.search(surface) and PARENTHETICAL.search(reading):
                surface = PARENTHETICAL.sub("", surface)
                reading = PARENTHETICAL.sub("", reading)
            if surface and (reading := clean_reading(reading)):
                readings[code, surface].add(reading)
    return readings


def locality_key(row, field):
    parent = row["machiaza_id"][:4] if field == "koaza" else ""
    return field, row["lg_code"], parent, row[field]


def unique_reading(readings):
    return next(iter(readings)) if len(readings) == 1 else ""


def resolve_reading(row, field, local, postal):
    if reading := clean_reading(row[f"{field}_kana"]):
        return reading, "addressBaseRegistry"
    if reading := unique_reading(local.get(locality_key(row, field), ())):
        return reading, "addressBaseRegistryMatch"
    surface = unicodedata.normalize("NFKC", row[field])
    if field == "oaza_cho" or not row["oaza_cho"]:
        if reading := unique_reading(postal.get((row["lg_code"][:5], surface), ())):
            return reading, "japanPost"
    for prefix, kana in (("大字", "オオアザ"), ("字", "アザ")):
        if surface.startswith(prefix) and (reading := clean_reading(surface[len(prefix):])):
            return kana + reading, "literalKana"
    return clean_reading(surface), "literalKana"


def speak_number(text):
    if text.startswith("0") or len(text) > 4:
        return "".join(DIGITS[int(digit)] for digit in text)
    thousands, hundreds, tens, ones = map(int, text.zfill(4))
    ten = (DIGITS[tens] if tens > 1 else "") + "ジュウ" if tens else ""
    return THOUSANDS[thousands] + HUNDREDS[hundreds] + ten + (DIGITS[ones] if ones else "")


def reading_variants(reading):
    yield reading
    if not any("0" <= character <= "9" for character in reading):
        return

    def pronounce(match):
        kana = speak_number(match[0])
        if reading.startswith("チョウメ", match.end()):
            for suffix, changed in CHOME_ENDINGS.items():
                if kana.endswith(suffix):
                    return kana.removesuffix(suffix) + changed
        return kana

    yield re.sub(r"[0-9]+", pronounce, reading)


def collect_town_pairs(archive, postal_archive, as_of, characters):
    local = defaultdict(set)
    postal = postal_readings(postal_archive)
    prefectures = set()
    for row in town_rows(archive, as_of):
        prefectures.add(row["lg_code"][:2])
        for field in ("oaza_cho", "koaza"):
            if row[field] and (reading := clean_reading(row[f"{field}_kana"])):
                local[locality_key(row, field)].add(reading)
    if len(prefectures) != 47:
        raise ValueError("The town master must cover all 47 prefectures")

    pairs = defaultdict(set)
    counts = Counter()
    unresolved = []
    for row in town_rows(archive, as_of):
        counts["rows"] += 1
        components = {}
        for field in FIELDS:
            if surface := row[field]:
                reading, origin = resolve_reading(row, field, local, postal)
                components[field] = (reading, surface, origin)
        for child in ("chome", "koaza"):
            if "oaza_cho" in components and child in components:
                parent_reading, parent_name, parent_source = components["oaza_cho"]
                reading, surface, origin = components[child]
                if parent_reading and reading and parent_name != surface:
                    sources = "+".join(sorted({parent_source, origin}))
                    components[f"oaza_cho+{child}"] = (parent_reading + reading, parent_name + surface, sources)
        for field, (reading, surface, origin) in components.items():
            excluded = Counter()
            has_valid_reading = False
            if reading:
                for variant in reading_variants(reading):
                    if dictionary.valid_word(variant, surface, characters, excluded, allow_digits=True):
                        pairs[variant, surface].add((field, origin))
                        has_valid_reading = True
            if has_valid_reading:
                counts[f"covered:{field}"] += 1
                counts[f"source:{origin}"] += 1
            else:
                reason = next(iter(excluded), "missingReading")
                counts[f"excluded:{field}:{reason}"] += 1
                unresolved.append((row["lg_code"], row["machiaza_id"], field, reason, surface,
                                   reading or row.get(f"{field}_kana", "")))
    return pairs, counts, unresolved


def extend(previous, archive, postal, as_of, builder, output, report):
    for path in (output, report):
        if path.exists():
            raise FileExistsError(path)
    source, manifest, metadata, records = dictionary.verified_trial(previous)
    louds = source / "louds"
    characters = set((louds / "charID.chid").read_text(encoding="utf-8"))
    pairs, counts, unresolved = collect_town_pairs(archive, postal, as_of, characters)
    baseline, groups = dictionary.standard_entries(louds)
    picked = {
        key: ("proper", [{"field": field, "source": origin} for field, origin in sorted(provenance)])
        for key, provenance in pairs.items()
        if not any(row.surface == key[1] for row in baseline.get(key[0], ()))
    }
    del pairs
    print(f"Read {counts['rows']:,} town master rows. Compiling {len(picked):,} new reading/name pairs.", flush=True)
    if not picked:
        raise ValueError("No new town names were found")
    additions = list(dictionary.make_entries(picked, baseline))
    del picked, baseline
    metadata.pop("files")
    metadata["counts"]["total"] += len(additions)
    metadata["counts"]["townNames"] = dict(sorted(counts.items()), addedPairs=len(additions))
    metadata["sources"]["townNames"] = {
        "asOf": as_of, "baselineManifestSha256": dictionary.digest(manifest),
        "townMaster": {"url": TOWN_URL, "sha256": dictionary.digest(archive)},
        "postal": {"url": POSTAL_URL, "sha256": dictionary.digest(postal)},
    }
    dictionary.publish_dictionary(source, builder, output, groups, additions, metadata, records,
                                  extra_notices=(NOTICE,))
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("lg_code", "machiaza_id", "field", "reason", "surface", "reading"))
        writer.writerows(unresolved)
    print(f"Verified {len(additions):,} additions. Coverage details: {report}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--postal", type=Path, required=True)
    parser.add_argument("--as-of", type=date.fromisoformat, required=True)
    parser.add_argument("--builder", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--unresolved", type=Path, required=True)
    args = parser.parse_args()
    extend(args.previous, args.archive, args.postal, args.as_of.isoformat(), args.builder,
           args.output, args.unresolved)
