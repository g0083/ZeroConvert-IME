"""Prepare built web assets for Cloudflare Pages (25 MiB limit chunking, SHA-256 manifest, and _headers)."""

import argparse
import hashlib
import json
from pathlib import Path

CLOUDFLARE_LIMIT_BYTES = 24 * 1024 * 1024  # Safe margin below Cloudflare Pages 25 MiB per-file limit
DEFAULT_CHUNK_BYTES = 16 * 1024 * 1024

HEADERS_CONTENT = """/*
  Cross-Origin-Opener-Policy: same-origin
  Cross-Origin-Embedder-Policy: require-corp
  Cross-Origin-Resource-Policy: same-origin
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
        size = path.stat().st_size
        digest = sha256_file(path)
        entry = {"bytes": size, "sha256": digest}

        if size > args.max_file_bytes:
            parts = []
            with path.open("rb") as src:
                index = 0
                while True:
                    chunk = src.read(args.chunk_bytes)
                    if not chunk:
                        break
                    part_name = f"{path.name}.part{index:02d}"
                    (site_dir / part_name).write_bytes(chunk)
                    parts.append({"name": part_name, "bytes": len(chunk)})
                    index += 1
            path.unlink()
            entry["chunks"] = parts

        assets[path.name] = entry

    manifest["assets"] = assets
    manifest["hosting"] = {
        "platform": "cloudflare-pages",
        "coopCoep": True,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (site_dir / "_headers").write_text(HEADERS_CONTENT, encoding="utf-8")


if __name__ == "__main__":
    main()
