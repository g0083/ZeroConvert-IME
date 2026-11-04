"""Summarize locked ranking experiments; never change selection from test results."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

import numpy as np
from dataset_isolation import load_jsonl, sha256
from qwerty_corrector_policy import write_json
from qwerty_ranker import metrics, select


def contributions(row,record,margin):
    selected=select(record["candidates"],record["scores"],margin)
    repair=row["expected_action"]=="repair"
    good=selected is not None and repair and record["candidates"][selected]["reading"]==row["target_reading"]
    return np.array([int(good),int(selected is not None and not good),int(repair)],dtype=float)


def fscore(values):
    tp,fp,total=values
    return 1.25*tp/(tp+fp+.25*total) if total+fp else 0.


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--trained-dev",type=Path,required=True); p.add_argument("--trained-test",type=Path,required=True)
    p.add_argument("--reference",type=Path,required=True); p.add_argument("--prospective",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    trained=json.loads((a.trained_test/"comparison.json").read_text(encoding="utf-8"))
    reference=json.loads((a.reference/"comparison.json").read_text(encoding="utf-8"))
    if trained['status']!='completed' or reference['status']!='completed': raise ValueError("Incomplete evaluation")
    lock=a.trained_dev/"policy-lock.json"
    if sha256(lock)!=reference["reference_lock_sha256"]: raise ValueError("Reference uses different lock")
    rows=load_jsonl(a.prospective/"test.jsonl")
    if sha256(a.prospective/"test.jsonl")!=trained['identity']['files']['test']: raise ValueError("Test changed")
    records={"standard":load_jsonl(a.trained_test/"test-scores.jsonl"),"nano_prior":load_jsonl(a.reference/"test-scores.jsonl")}
    margins={"standard":trained['policy']['margin'],"nano_prior":reference['policy']['margin']}
    grouped={}; results={}
    for name,values in records.items():
        result=metrics(rows,values,margins[name]); by_type={}; active_keep=0; active_keep_fp=0
        group=defaultdict(lambda:np.zeros(3))
        for row,record in zip(rows,values,strict=True):
            if row['id']!=record['id']: raise ValueError("Row mismatch")
            group[row['source_group']]+=contributions(row,record,margins[name])
            if row['expected_action']=='keep' and len(record['candidates'])>1:
                active_keep+=1; active_keep_fp+=select(record['candidates'],record['scores'],margins[name]) is not None
        for kind in sorted({r['typo_type'] for r in rows}):
            pairs=[(r,v) for r,v in zip(rows,values) if r['typo_type']==kind]
            by_type[kind]=metrics([r for r,_ in pairs],[v for _,v in pairs],margins[name])
        results[name]={"metrics":result,"active_keep":active_keep,"active_keep_fp":active_keep_fp,"by_type":by_type}
        grouped[name]=group
    groups=sorted(grouped['standard']); matrices={k:np.stack([v[g] for g in groups]) for k,v in grouped.items()}
    rng=np.random.default_rng(42); differences=[]
    for _ in range(5000):
        draw=rng.integers(0,len(groups),len(groups))
        differences.append(fscore(matrices['standard'][draw].sum(0))-fscore(matrices['nano_prior'][draw].sum(0)))
    write_json(a.out,{"status":"completed","results":results,"paired_source_group_bootstrap":{
        "groups":len(groups),"seed":42,"draws":5000,"f0_5_standard_minus_nano_prior_95pct":np.percentile(differences,[2.5,97.5]).tolist()},
        "limits":"Different recipes, bases and quantization: system reference, not isolated Standard-vs-Nano capacity effect. Authored synthetic event regression; no real-use frequency guarantee.",
        "policy_lock_sha256":sha256(lock)})
    print(json.dumps({k:v['metrics'] for k,v in results.items()},ensure_ascii=False),flush=True)


if __name__=="__main__": main()
