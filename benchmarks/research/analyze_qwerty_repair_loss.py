"""Loss ablation audit: same data/base, free-generation copy failures, groups."""
import argparse
from collections import Counter
import json
from pathlib import Path
import random
import re

from analyze_qwerty_corrector import group_values,fscore
from analyze_qwerty_local import invalid_reason
from dataset_isolation import sha256
from qwerty_corrector_policy import write_json


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out-dir",type=Path,required=True)
    a=p.parse_args()
    out=a.out_dir/"loss-analysis.json"
    if out.exists():raise FileExistsError(out)
    comparison=json.loads((a.out_dir/"comparison.json").read_text(encoding="utf-8"))
    if comparison.get("status")!="completed":raise ValueError("Comparison incomplete")
    report={"comparison_sha256":sha256(a.out_dir/"comparison.json"),"training":{},"splits":{}}
    for label,model in comparison["models"].items():
        if label=="full":continue
        directory=Path("artifacts/checkpoints")/(model["run"]+"-lora")
        manifest=json.loads((directory/"run-manifest.json").read_text(encoding="utf-8"))
        provenance=json.loads((directory/"dataset-provenance.json").read_text(encoding="utf-8"))
        state=json.loads((directory/"trainer_state.json").read_text(encoding="utf-8"))
        calibration=json.loads((Path("artifacts/datasets")/(model["run"]+"-imatrix")/"manifest.json").read_text(encoding="utf-8"))
        report["training"][label]={"arguments":manifest["arguments"],"base":manifest["base"],
            "train_sha256":provenance["train"]["sha256"],"val_sha256":provenance["validation"]["sha256"],
            "selected_checkpoint":state["best_model_checkpoint"],"best_metric":state["best_metric"],"calibration_sha256":calibration["calibration_sha256"]}
    baseline=report["training"]["local"]
    for row in report["training"].values():
        assert row["base"]==baseline["base"] and row["train_sha256"]==baseline["train_sha256"] and row["val_sha256"]==baseline["val_sha256"]
        assert row["calibration_sha256"]==baseline["calibration_sha256"]
    fixtures={"val":Path("artifacts/checkpoints")/(comparison["models"]["local"]["run"]+"-lora")/"validation-fixture.json",
        "test":Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json"),"fresh":Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json")}
    for split,path in fixtures.items():
        rows=json.loads(path.read_text(encoding="utf-8"))["cases"];lookup={r["id"]:r for r in rows}
        models={label:json.loads((a.out_dir/f"{split}-{label}.json").read_text(encoding="utf-8")) for label in report["training"]}
        report["splits"][split]={}
        base_groups=group_values(rows,models["local"]["physical_report"])
        keys=sorted(base_groups)
        for label,data in models.items():
            identity=0;repair_identity=0;repair_edit_noop=0;reasons=Counter()
            for item in data["requests"]:
                pred=item["prediction"];row=lookup[item["id"]]
                match=re.fullmatch(r"E:([a-z-]{1,12}):([a-z-]{0,14})",pred["text"])
                if match and match.group(1)==match.group(2):
                    identity+=1;repair_identity+=row["expected_action"]=="repair"
                if match and pred["valid"] and pred["action"]=="K" and pred.get("keys") and row["expected_action"]=="repair":
                    repair_edit_noop+=1
                if not pred["valid"]:reasons[invalid_reason(pred["text"],row)]+=1
            values={"identity_edits":identity,"identity_edits_on_repair":repair_identity,"reading_unchanged_edits_on_repair":repair_edit_noop,"invalid_reasons":dict(reasons)}
            if split!="val" and label!="local":
                new_groups=group_values(rows,data["physical_report"]);rng=random.Random(42);deltas=[]
                for _ in range(5000):
                    sample=rng.choices(keys,k=len(keys))
                    scores=[fscore([sum(g[k][i] for k in sample) for i in range(3)]) for g in (base_groups,new_groups)]
                    deltas.append(scores[1]-scores[0])
                deltas.sort();values.update(groups=len(keys),delta_f0_5_95_percentile_interval=[deltas[125],deltas[4874]],seed=42,draws=5000)
            report["splits"][split][label]=values
    write_json(out,report)
    print(json.dumps(report["splits"],ensure_ascii=False,indent=2))


if __name__=="__main__":main()
