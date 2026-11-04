"""Rebind frozen independent normal responses, retaining identical case IDs."""
import argparse
import json
from pathlib import Path
from dataset_isolation import load_jsonl, sha256


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset", type=Path, required=True)
    p.add_argument("--prefix", required=True)
    a = p.parse_args()
    for split in ("val", "test"):
        old = Path(f"artifacts/results/corrector-qwerty-native-20261030-r3-{split}-fixture.json")
        fixture = json.loads(old.read_text(encoding="utf-8"))
        rows = {r["id"]: r for r in load_jsonl(a.dataset / f"{split}.jsonl")}
        for case in fixture["cases"]:
            source = rows[case["id"]]
            for key in ("prompt", "expected", "observed_keys", "expected_action"):
                if case[key] != source[key]:
                    raise ValueError(f"Case changed: {key}")
            case.update(input_state=source["input_state"], cursor_key_offset=source["cursor_key_offset"])
        fixture.update(dataset_manifest_sha256=sha256(a.dataset / "manifest.json"), case_file_sha256=sha256(a.dataset / f"{split}.jsonl"),
                       state_source_fixture_sha256=sha256(old))
        out = Path(f"artifacts/results/{a.prefix}-{split}-fixture.json")
        if out.exists():
            raise FileExistsError(out)
        out.write_text(json.dumps(fixture, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        if split == "val":
            prior = Path("artifacts/results/corrector-qwerty-native-20261030-r3-val-baseline.json")
            baseline = json.loads(prior.read_text(encoding="utf-8"))
            if baseline["fixture_sha256"] != sha256(old):
                raise ValueError("Old baseline changed")
            baseline.update(fixture_sha256=sha256(out), rebound_baseline_sha256=sha256(prior))
            target = Path(f"artifacts/results/{a.prefix}-val-baseline.json")
            if target.exists():
                raise FileExistsError(target)
            target.write_text(json.dumps(baseline, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")


if __name__ == "__main__":
    main()
