"""Extract the released default model and stage isolated Windows IME settings."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    deployment = Path(__file__).resolve().parents[1] / "windows/deployment"
    settings = json.loads((deployment / "settings.json").read_text(encoding="utf-8"))
    zenzai = settings["zenzai"]
    catalog = json.loads((deployment / "model-catalog.json").read_text(encoding="utf-8"))
    model = next(row for row in catalog["models"] if row["id"] == zenzai["model"])
    with ZipFile(args.apk) as apk:
        files = [entry for entry in apk.infolist() if PurePosixPath(entry.filename).name == model["filename"]]
        if len(files) != 1 or files[0].file_size != model["bytes"]:
            raise ValueError("Released APK does not contain the expected default model")
        payload = apk.read(files[0])
    if hashlib.sha256(payload).hexdigest() != model["sha256"]:
        raise ValueError("Released model differs from the Windows catalog")
    (args.output / model["filename"]).write_bytes(payload)
    zenzai.update(backend="cpu", gpu_layers=0)
    settings["learning"]["mode"] = "none"
    (args.output / "ime-settings.json").write_text(json.dumps(settings, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
