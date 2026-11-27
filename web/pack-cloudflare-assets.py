"""Prepare built web assets for Cloudflare Pages (gzip for large Wasm, 25 MiB chunking, SHA-256 manifest, and _headers)."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path

CLOUDFLARE_LIMIT_BYTES = 24 * 1024 * 1024  # Safe margin below Cloudflare Pages 25 MiB per-file limit
DEFAULT_CHUNK_BYTES = 16 * 1024 * 1024

# Note: COOP/COEP headers are intentionally omitted while running single-threaded WASI
# so browser extensions (e.g. Vivaldi content scripts) do not hit cross-origin isolation conflicts.
HEADERS_CONTENT = """/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin

/index.html
  Cache-Control: public, max-age=0, must-revalidate

/
  Cache-Control: public, max-age=0, must-revalidate

/asset-manifest.json
  Cache-Control: public, max-age=0, must-revalidate

/ime-settings.json
  Cache-Control: public, max-age=0, must-revalidate

/*.wasm
  Cache-Control: public, max-age=604800, stale-while-revalidate=86400

/*.gguf
  Cache-Control: public, max-age=604800, stale-while-revalidate=86400

/*.gz
  Cache-Control: public, max-age=604800, stale-while-revalidate=86400

/*.part*
  Cache-Control: public, max-age=604800, stale-while-revalidate=86400
"""

TRACKED_EXTENSIONS = {".wasm", ".gguf", ".gz"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Built static site directory")
    parser.add_argument("--max-file-bytes", type=int, default=CLOUDFLARE_LIMIT_BYTES)
    parser.add_argument("--chunk-bytes", type=int, default=DEFAULT_CHUNK_BYTES)
    args = parser.parse_args()

    site_dir = args.directory.resolve()
    manifest_path = site_dir / "asset-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {"schemaVersion": 1}
    assets = {}

    for path in sorted(site_dir.iterdir()):
        if not path.is_file() or path.suffix not in TRACKED_EXTENSIONS:
            continue
        logical_name = path.name
        raw_size = path.stat().st_size
        delivery_path = path
        use_gzip = False

        # Pre-compress large Wasm binaries (e.g. 52 MiB Swift Wasm -> ~16 MiB .wasm.gz)
        # so downloads are ~3x faster and fit under Cloudflare Pages' 25 MiB per-file limit.
        if path.suffix == ".wasm" and raw_size > args.max_file_bytes:
            gz_path = site_dir / f"{path.name}.gz"
            with path.open("rb") as src, gz_path.open("wb") as raw_out:
                with gzip.GzipFile(fileobj=raw_out, mode="wb", compresslevel=9, mtime=0) as gz:
                    for block in iter(lambda: src.read(1024 * 1024), b""):
                        gz.write(block)
            path.unlink()
            delivery_path = gz_path
            use_gzip = True

        size = delivery_path.stat().st_size
        digest = sha256_file(delivery_path)
        entry = {"bytes": size, "sha256": digest}
        if use_gzip:
            entry["file"] = delivery_path.name
            entry["gzip"] = True
            entry["rawBytes"] = raw_size

        if size > args.max_file_bytes:
            parts = []
            with delivery_path.open("rb") as src:
                index = 0
                while True:
                    chunk = src.read(args.chunk_bytes)
                    if not chunk:
                        break
                    part_name = f"{delivery_path.name}.part{index:02d}"
                    (site_dir / part_name).write_bytes(chunk)
                    parts.append({"name": part_name, "bytes": len(chunk)})
                    index += 1
            delivery_path.unlink()
            entry["chunks"] = parts

        assets[logical_name] = entry

    manifest["assets"] = assets
    manifest["hosting"] = {
        "platform": "cloudflare-pages",
        "coopCoep": False,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (site_dir / "_headers").write_text(HEADERS_CONTENT, encoding="utf-8")


if __name__ == "__main__":
    main()
