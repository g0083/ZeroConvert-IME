"""Fetch or extract the default released model, stage isolated Windows IME settings, and write asset-manifest.json."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import urllib.request
from zipfile import ZipFile

USER_AGENT = "ZeroConvert-IME-WebBuilder/1.0 (+https://github.com/g0083/ZeroConvert-IME)"


def git_revision(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()
    except Exception:
        return "unknown"


def download_from_catalog(catalog: dict, model: dict, cache_dir: Path | None) -> bytes:
    base_url = catalog["downloadBaseUrl"].rstrip("/")
    url = f"{base_url}/{model['filename']}"
    cache_path = cache_dir / f"{model['sha256'][:12]}-{model['filename']}" if cache_dir else None
    if cache_path and cache_path.is_file():
        cached = cache_path.read_bytes()
        if len(cached) == model["bytes"] and hashlib.sha256(cached).hexdigest() == model["sha256"]:
            return cached

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=180) as response:
        payload = response.read()
    if len(payload) != model["bytes"]:
        raise ValueError(
            f"Downloaded model size mismatch for {url}: expected {model['bytes']} bytes, got {len(payload)} bytes"
        )
    if hashlib.sha256(payload).hexdigest() != model["sha256"]:
        raise ValueError(f"Downloaded model SHA-256 mismatch for {url}")
    if cache_path:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_bytes(payload)
    return payload


def extract_from_apk(apk_path: Path, model: dict) -> bytes:
    with ZipFile(apk_path) as apk:
        files = [entry for entry in apk.infolist() if PurePosixPath(entry.filename).name == model["filename"]]
        if len(files) != 1 or files[0].file_size != model["bytes"]:
            raise ValueError("Released APK does not contain the expected default model")
        payload = apk.read(files[0])
    if hashlib.sha256(payload).hexdigest() != model["sha256"]:
        raise ValueError("Released model differs from the Windows catalog")
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "first",
        type=Path,
        help="Output directory (catalog mode) or legacy APK path (when second positional argument is given)",
    )
    parser.add_argument("second", type=Path, nargs="?", help="Output directory when passing an APK path")
    parser.add_argument("--apk", type=Path, help="Optional APK path to extract the model from instead of downloading")
    parser.add_argument("--model-file", type=Path, help="Optional pre-downloaded local GGUF file")
    parser.add_argument("--cache-dir", type=Path, help="Optional cache directory for downloaded catalog models")
    args = parser.parse_args()

    if args.second is not None:
        apk_path = args.first
        output_dir = args.second
    else:
        apk_path = args.apk
        output_dir = args.first

    root = Path(__file__).resolve().parents[1]
    deployment = root / "windows/deployment"
    settings = json.loads((deployment / "settings.json").read_text(encoding="utf-8"))
    zenzai = settings["zenzai"]
    catalog = json.loads((deployment / "model-catalog.json").read_text(encoding="utf-8"))
    model = next(row for row in catalog["models"] if row["id"] == zenzai["model"])

    if args.model_file:
        payload = args.model_file.read_bytes()
        if len(payload) != model["bytes"] or hashlib.sha256(payload).hexdigest() != model["sha256"]:
            raise ValueError("Provided local model file does not match windows/deployment/model-catalog.json")
    elif apk_path:
        payload = extract_from_apk(apk_path, model)
    else:
        payload = download_from_catalog(catalog, model, args.cache_dir)

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / model["filename"]).write_bytes(payload)
    zenzai.update(backend="cpu", gpu_layers=0)
    settings["learning"]["mode"] = "disabled"
    (output_dir / "ime-settings.json").write_text(json.dumps(settings, ensure_ascii=False), encoding="utf-8")

    manifest_path = output_dir / "asset-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    manifest["schemaVersion"] = 1
    manifest["model"] = {
        "id": model["id"],
        "name": model["name"],
        "filename": model["filename"],
        "bytes": model["bytes"],
        "sha256": model["sha256"],
        "releaseTag": catalog.get("releaseTag", ""),
    }
    manifest["build"] = {
        "commit": git_revision(root),
        "builtAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
