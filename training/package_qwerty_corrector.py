"""Save a named QWERTY research candidate; never change the runtime model store."""
import argparse
import json
from pathlib import Path
import shutil

from dataset_isolation import sha256


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--selection", type=Path, required=True)
    p.add_argument("--comparison", type=Path, required=True)
    p.add_argument("--out-name", default="zerocon-corrector-qwerty-v4-q4km")
    a = p.parse_args()
    if Path(a.out_name).name != a.out_name:
        raise ValueError("Expected a single file name")
    selection = json.loads(a.selection.read_text(encoding="utf-8"))
    measured = json.loads(a.comparison.read_text(encoding="utf-8"))
    chosen = selection["models"][selection["selected"]]
    model = chosen["model"]
    if not chosen["eligible"] or measured["status"] != "completed" or not measured["replay_live_decisions_identical"]:
        raise ValueError("Missing validation/test evidence")
    if measured["selection_sha256"] != sha256(a.selection) or measured["selected"] != model or sha256(model["path"]) != model["sha256"]:
        raise ValueError("Selected artifact identity changed")
    out = Path("artifacts/models") / f"{a.out_name}.gguf"
    card = out.with_suffix(".json")
    if out.exists() or card.exists():
        raise FileExistsError(out)
    shutil.copyfile(model["path"], out)
    if sha256(out) != model["sha256"]:
        raise ValueError("Candidate copy differs")
    payload = {"name": a.out_name, "status": "research-candidate; not a general-corrector replacement",
        "input_method": "qwerty", "format": "Q4_K_M", "bytes": out.stat().st_size,
        "sha256": sha256(out), "source": model, "validation_selected_alpha": selection["selected"],
        "parent_hf": "artifacts/checkpoints/corrector-v4-nano-20261030-r1-merged",
        "adapted_hf": "artifacts/checkpoints/corrector-qwerty-v4-20261030-r1-merged",
        "selection": {"path": str(a.selection), "sha256": sha256(a.selection)},
        "comparison": {"path": str(a.comparison), "sha256": sha256(a.comparison), "results": measured["results"]},
        "limits": ["Synthetic native-key cohort improved; legacy-labelled cohort precision regressed",
            "Source groups held out for this run, not historically untouched pretraining groups",
            "No TSF/display/real-user evaluation, no runtime installation or catalog change",
            "Reading prompt contract retained; raw keys are dataset provenance, not additional inference input",
            "Flick errors excluded from QWERTY adaptation; no flick-specific model created"],
        "report": "reports/typo-assist/corrector-qwerty-v4-20261030.md"}
    card.write_text(json.dumps(payload, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(out, payload["sha256"])


if __name__ == "__main__":
    main()
