"""Audit matched training inputs and diagnose local instruction failures."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import re
from analyze_qwerty_corrector import group_values,fscore
from dataset_isolation import load_jsonl,sha256
from qwerty_corrector_policy import write_json
from qwerty_local_contract import one_edit
from qwerty_local_eval import input_prompt


def invalid_reason(text,row):
    keys=row.get("observed_keys")
    match=re.fullmatch(r"E:([a-z-]{1,12}):([a-z-]{0,14})",text)
    if not match:return "malformed-or-incomplete"
    if not keys:return "raw-unavailable"
    old,new=match.groups()
    if old not in keys:return "anchor-absent"
    if keys.find(old)!=keys.rfind(old):return "anchor-not-unique"
    corrected=keys.replace(old,new,1)
    if corrected==keys:return "no-change-edit"
    if not one_edit(keys,corrected):return "not-one-physical-edit"
    return "native-incomplete-or-out-of-range"


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out-dir",type=Path,required=True)
    a=p.parse_args()
    target=a.out_dir/"analysis.json"
    if target.exists():raise FileExistsError(target)
    comparison=json.loads((a.out_dir/"comparison.json").read_text(encoding="utf-8"))
    if comparison.get("status")!="completed":raise ValueError("Comparison incomplete")
    data=Path("artifacts/datasets")
    left=load_jsonl(data/"corrector-qwerty-local-20261030-r2/train.jsonl")
    right=load_jsonl(data/"corrector-qwerty-local-20261030-r4/train.jsonl")
    def view(rows):return [(r["id"],input_prompt(r),r["completion"],3 if r["expected_action"]=="keep" else 1) for r in rows]
    assert view(left)==view(right)
    report={"comparison_sha256":sha256(a.out_dir/"comparison.json"),"effective_training_input_and_full_target_equality":True,
        "effective_input_and_full_target_sha256":hashlib.sha256(json.dumps(view(left),ensure_ascii=False).encode()).hexdigest(),
        "repaired_local_labels":sum(x["local_completion"]!=y["local_completion"] for x,y in zip(left,right)),"splits":{}}
    fixtures={"val":Path("artifacts/checkpoints/corrector-qwerty-full-long-20261030-r1-lora/validation-fixture.json"),
        "test":Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json"),
        "fresh":Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json")}
    for split,path in fixtures.items():
        rows=json.loads(path.read_text(encoding="utf-8"))["cases"]
        models={m:json.loads((a.out_dir/f"{split}-{m}.json").read_text(encoding="utf-8")) for m in ("full","local")}
        lookup={r["id"]:r for r in rows}
        reasons=Counter(); actions=Counter(); repair_invalid=0
        for item in models["local"]["requests"]:
            pred=item["prediction"]; row=lookup[item["id"]]
            actions[pred["action"]]+=1
            if not pred["valid"]:
                reasons[invalid_reason(pred["text"],row)]+=1
                repair_invalid+=row["expected_action"]=="repair"
        values={"local_invalid_reasons":dict(reasons),"local_actions":dict(actions),"local_invalid_on_repair":repair_invalid}
        if split!="val":
            groups=[group_values(rows,models[m]["physical_report"]) for m in ("full","local")]
            keys=sorted(groups[0]);rng=random.Random(42);deltas=[]
            for _ in range(5000):
                sample=rng.choices(keys,k=len(keys))
                scores=[fscore([sum(g[k][i] for k in sample) for i in range(3)]) for g in groups]
                deltas.append(scores[1]-scores[0])
            deltas.sort()
            values.update(groups=len(keys),local_minus_full_f0_5_95_percentile_interval=[deltas[125],deltas[4874]],draws=5000,seed=42)
        report["splits"][split]=values
    write_json(target,report)
    print(json.dumps(report["splits"],ensure_ascii=False,indent=2))


if __name__=="__main__":main()
