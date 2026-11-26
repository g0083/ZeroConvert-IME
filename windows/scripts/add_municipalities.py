"""Add official Japanese prefecture and municipality names to a trial dictionary."""

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date
import io
from pathlib import Path
import zipfile

import additional_dictionary as dictionary


SOURCE_URL = "https://data.address-br.digital.go.jp/mt_city/mt_city_all.csv.zip"
NOTICE = dictionary.WINDOWS.parent / "data/dictionary/AdditionalDictionaryNotices/AddressBaseRegistry.txt"


def name_pairs(archive_path, as_of):
    pairs = defaultdict(set)
    with zipfile.ZipFile(archive_path) as archive:
        with io.TextIOWrapper(archive.open("mt_city_all.csv"), encoding="utf-8-sig", newline="") as csv_file:
            for row in csv.DictReader(csv_file):
                if row["efct_date"] > as_of or (row["ablt_date"] and row["ablt_date"] <= as_of):
                    continue
                variants = [("pref",), ("city",)]
                if row["ward"]:
                    variants.extend([("ward",), ("city", "ward")])
                for fields in variants:
                    reading = "".join(dictionary.katakana(row[f"{field}_kana"]) for field in fields)
                    surface = "".join(row[field] for field in fields)
                    if not all(row[field] and row[f"{field}_kana"] for field in fields):
                        raise ValueError(f"Missing name or reading for {row['lg_code']}: {fields}")
                    pairs[reading, surface].add("+".join(fields))
    return pairs


def extend(previous, archive, as_of, builder, output):
    if output.exists():
        raise FileExistsError(output)
    source, manifest, metadata, records = dictionary.verified_trial(previous)

    pairs = name_pairs(archive, as_of)
    if sum("pref" in kinds for kinds in pairs.values()) != 47:
        raise ValueError("The source must cover all 47 prefectures")
    louds = source / "louds"
    baseline, groups = dictionary.standard_entries(louds)
    characters = set((louds / "charID.chid").read_text(encoding="utf-8"))
    picked = {}
    for (reading, surface), kinds in pairs.items():
        if not dictionary.valid_word(reading, surface, characters, Counter()):
            raise ValueError(f"Unsupported official name or reading: {reading} {surface}")
        if not any(row.surface == surface for row in baseline.get(reading, ())):
            picked[reading, surface] = ("proper", [{"source": "addressBaseRegistry", "fields": sorted(kinds)}])
    additions = list(dictionary.make_entries(picked, baseline))
    if not additions:
        print(f"All {len(pairs):,} official name-reading pairs are already present")
        return

    metadata.pop("files")
    metadata["counts"]["total"] += len(additions)
    metadata["counts"]["addressBaseRegistry"] = len(additions)
    metadata["sources"]["addressBaseRegistry"] = {
        "url": SOURCE_URL, "sha256": dictionary.digest(archive), "asOf": as_of,
        "baselineManifestSha256": dictionary.digest(manifest),
    }
    dictionary.publish_dictionary(source, builder, output, groups, additions, metadata, records,
                                  extra_notices=(NOTICE,))
    print(f"Generated {len(additions):,} new entries covering {len(pairs):,} official name-reading pairs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--as-of", type=date.fromisoformat, required=True)
    parser.add_argument("--builder", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    extend(args.previous, args.archive, args.as_of.isoformat(), args.builder, args.output)
