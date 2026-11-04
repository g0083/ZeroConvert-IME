"""Add simulated observable edit state; verify deletion with the native DLL."""
import argparse
import ctypes
import json
from pathlib import Path

from dataset_isolation import load_jsonl, sha256
from native_qwerty import NativeQwerty


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--parent", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    native = NativeQwerty()
    manifest = json.loads((a.parent / "manifest.json").read_text(encoding="utf-8"))
    outputs = {}
    checked = 0
    parts = {}
    for split in ("train", "val", "test"):
        path = a.parent / f"{split}.jsonl"
        if sha256(path) != manifest["outputs"][split]["sha256"]:
            raise ValueError("Parent changed")
        rows = load_jsonl(path)
        for row in rows:
            keys = row.get("observed_keys")
            state = "unknown" if keys is None else "backspace" if row["typo_type"] == "editing_prefix" else "append"
            if state == "backspace":
                native.clear()
                for key in row["canonical_keys"]:
                    value = native.take(native.dll.AppendText(key.encode("ascii"), ctypes.byref(native.cursor)))
                while len(native.take(native.dll.GetRawInput())) > len(keys):
                    value = native.take(native.dll.RemoveText(ctypes.byref(native.cursor)))
                if native.take(native.dll.GetRawInput()) != keys or value != row["native_observed"]:
                    raise ValueError("Deletion differs from fresh typing")
                checked += 1
            row["input_state"] = state
            row["cursor_key_offset"] = len(keys) if keys is not None else None
        parts[split] = rows
    a.out.mkdir(parents=True)
    for split, rows in parts.items():
        path = a.out / f"{split}.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False)+"\n" for r in rows), encoding="utf-8")
        outputs[split] = {**manifest["outputs"][split], "sha256": sha256(path)}
    manifest.update(outputs=outputs, state_parent=str(a.parent), state_parent_manifest_sha256=sha256(a.parent / "manifest.json"),
        state_code_sha256=sha256(__file__), native_backspace_verified=checked,
        state_scope="Simulated append/backspace at end; unknown for rows without raw; not real user traces")
    (a.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print("Backspace verified", checked)


if __name__ == "__main__":
    main()
