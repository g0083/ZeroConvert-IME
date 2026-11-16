"""Package the pinned azooKey dictionary for the browser WASI filesystem."""

import argparse
import gzip
import json
from pathlib import Path
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dictionary", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    for directory in ("cb", "louds", "p"):
        if not (args.dictionary / directory).is_dir():
            parser.error(f"missing dictionary directory: {directory}")
    paths = [args.dictionary / "mm.binary"]
    for directory in ("cb", "louds", "p"):
        paths.extend(path for path in sorted((args.dictionary / directory).rglob("*"))
                     if path.is_file() and not path.name.startswith("."))
    manifest = json.dumps([[path.relative_to(args.dictionary).as_posix(), path.stat().st_size]
                           for path in paths], ensure_ascii=False).encode()
    with args.output.open("wb") as output:
        with gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as archive:
            archive.write(struct.pack("<I", len(manifest)))
            archive.write(manifest)
            for path in paths:
                archive.write(path.read_bytes())


if __name__ == "__main__":
    main()
