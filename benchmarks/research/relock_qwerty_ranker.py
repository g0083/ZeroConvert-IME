"""Reuse unchanged development scores after prospective-label audit, before test."""
import argparse
import json
from pathlib import Path

from dataset_isolation import load_jsonl, sha256
from evaluate_qwerty_ranker import timing
from qwerty_corrector_policy import write_json
from qwerty_ranker import calibrate, metrics


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--development",type=Path,required=True); p.add_argument("--prospective",type=Path,required=True)
    p.add_argument("--validation",type=Path,required=True); p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    old=json.loads((a.development/"comparison.json").read_text(encoding="utf-8"))
    if old["status"]!="completed": raise ValueError("Incomplete development scores")
    identity=old["identity"]
    if sha256(a.validation)!=identity["files"]["val"] or sha256(a.prospective/"dev.jsonl")!=identity["files"]["dev"]:
        raise ValueError("Development inputs changed; recompute scores")
    if sha256(identity["model"])!=identity["model_sha256"] or sha256("training/qwerty_ranker.py")!=identity["contract_sha256"]:
        raise ValueError("Model/contract changed")
    identity["files"]["test"]=sha256(a.prospective/"test.jsonl")
    rows=load_jsonl(a.validation); records=load_jsonl(a.development/"val-scores.jsonl")
    _,grid=calibrate(rows,records)
    eligible=[x for x in grid if x["metrics"]["keep_proposed"]==0 and x["metrics"]["wait_proposed"]==0]
    policy=max(eligible,key=lambda x:(x["metrics"]["f0_5"],-x["metrics"]["wrong_proposed"],x["margin"]))
    a.out.mkdir(parents=True)
    result={"identity":identity,"status":"completed","policy":policy,"reuse_source_sha256":sha256(a.development/"comparison.json")}
    for split,path in (("val",a.validation),("dev",a.prospective/"dev.jsonl")):
        records=load_jsonl(a.development/f"{split}-scores.jsonl")
        result[split]={"metrics":metrics(load_jsonl(path),records,policy["margin"]),"timing":timing(records)}
    write_json(a.out/"policy-lock.json",{"identity":identity,"policy":policy,"grid":grid,"status":"frozen-before-test",
        "development_scores":{s:sha256(a.development/f"{s}-scores.jsonl") for s in ("val","dev")}})
    write_json(a.out/"comparison.json",result)


if __name__=="__main__": main()
