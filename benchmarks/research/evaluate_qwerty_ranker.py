"""CPU GGUF ranking with validation-only calibration and explicit test lock."""
import argparse
import json
import math
from pathlib import Path
import time

import numpy as np
from benchmark_keyboard_native import NativeScorer
from dataset_isolation import load_jsonl, sha256
from qwerty_corrector_policy import write_json
from qwerty_ranker import calibrate, encode_candidates, metrics


class RankingScorer(NativeScorer):
    def score_observation(self,observation,candidates):
        encoded=encode_candidates(self.tokenizer,observation,candidates)
        if len(encoded)<=1: return [0.]*len(encoded)
        # Respect native total-token limits. Fixed 16-way chunks keep the
        # evaluation batch shape independent of the hidden target labels.
        sums=[]
        for start in range(0,len(encoded),16):
            chunk=encoded[start:start+16]
            request=str(len(chunk))+"\n"+"".join(f"{len(e['input_ids'])} {e['prefix_length']} "+" ".join(map(str,e['input_ids']))+"\n" for e in chunk)
            self.proc.stdin.write(request); self.proc.stdin.flush()
            values=[float(v) for v in self.read().split()]
            if len(values)!=len(chunk): raise ValueError("Native score count mismatch")
            sums.extend(values)
        if len(sums)!=len(encoded) or not all(math.isfinite(v) for v in sums): raise ValueError("Invalid native ranking scores")
        return [s/e["answer_tokens"] for s,e in zip(sums,encoded)]


def scored_rows(scorer,rows,path):
    records=[]
    with path.open("x",encoding="utf-8") as stream:
        for i,row in enumerate(rows):
            started=time.perf_counter()
            scores=scorer.score_observation(row,row["candidates"])
            elapsed=(time.perf_counter()-started)*1000
            record={"id":row["id"],"candidates":row["candidates"],"scores":scores,"score_ms":elapsed}
            stream.write(json.dumps(record,ensure_ascii=False)+"\n"); records.append(record)
            if i%25==0: stream.flush(); print(path.stem,i,len(rows),flush=True)
    return records


def timing(records):
    values=[r["score_ms"] for r in records]
    active=[r["score_ms"] for r in records if len(r["candidates"])>1]
    return {"all_p50_ms":float(np.percentile(values,50)),"all_p95_ms":float(np.percentile(values,95)),
        "active_count":len(active),"active_p50_ms":float(np.percentile(active,50)) if active else None,
        "active_p95_ms":float(np.percentile(active,95)) if active else None,
        "scope":"CPU candidate scoring only; excludes search, surface conversion, process startup, TSF and UI"}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model",type=Path,required=True); p.add_argument("--tokenizer",type=Path,required=True)
    p.add_argument("--dataset",type=Path,default=Path("artifacts/datasets/standard-qwerty-ranker-20261103-r1"))
    p.add_argument("--prospective",type=Path,default=Path("artifacts/datasets/standard-qwerty-ranker-prospective-20261103-r2"))
    p.add_argument("--out",type=Path,required=True); p.add_argument("--lock",type=Path)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    a.out.mkdir(parents=True)
    executable=Path("artifacts/build/qwerty-ranker-20261103-r1/qwerty-ranker-score.exe")
    files={"val":a.dataset/"val.jsonl","dev":a.prospective/"dev.jsonl","test":a.prospective/"test.jsonl"}
    dm=json.loads((a.dataset/"manifest.json").read_text(encoding="utf-8")); pm=json.loads((a.prospective/"manifest.json").read_text(encoding="utf-8"))
    for s,path in files.items():
        expected=(dm if s=="val" else pm)["outputs"][s]["sha256"]
        if sha256(path)!=expected: raise ValueError("Changed dataset")
    identity={"model":str(a.model),"model_sha256":sha256(a.model),"executable_sha256":sha256(executable),
        "tokenizer":{p.name:sha256(p) for p in a.tokenizer.iterdir() if p.suffix in (".json",".txt")},
        "contract_sha256":sha256("training/qwerty_ranker.py"),"files":{s:sha256(path) for s,path in files.items()}}
    results={"identity":identity,"scope":"Reading ranking; not surface accuracy or real-user evidence","status":"evaluating"}
    write_json(a.out/"comparison.json",results)
    if a.lock:
        locked=json.loads(a.lock.read_text(encoding="utf-8"))
        if locked["identity"]!=identity: raise ValueError("Lock does not match model/data/contract")
        policy=locked["policy"]
    else: policy=None
    scorer=RankingScorer(executable,a.model,Path("ZeroConvertIME-windows/llama_cpu"),a.tokenizer,a.out/"native.log")
    try:
        # Excluded warmup on development input only, including heldout runs.
        warm=next(r for r in load_jsonl(files["val"]) if len(r["candidates"])>1)
        scorer.score_observation(warm,warm["candidates"])
        for split in (("test",) if a.lock else ("val","dev")):
            rows=load_jsonl(files[split]); records=scored_rows(scorer,rows,a.out/f"{split}-scores.jsonl")
            if split=="val":
                policy,grid=calibrate(rows,records)
                # Prefer fewer wrong proposals on equal F0.5, including the
                # untrained parent's zero-recall case. Never inspect test.
                eligible=[x for x in grid if x["metrics"]["keep_proposed"]==0 and x["metrics"]["wait_proposed"]==0]
                policy=max(eligible,key=lambda x:(x["metrics"]["f0_5"],-x["metrics"]["wrong_proposed"],x["margin"]))
                write_json(a.out/"policy-lock.json",{"identity":identity,"policy":policy,"grid":grid,
                    "validation_scores_sha256":sha256(a.out/"val-scores.jsonl"),"status":"frozen-before-test"})
            results[split]={"metrics":metrics(rows,records,policy["margin"]),"timing":timing(records)}
            write_json(a.out/"comparison.json",results); print(split,results[split],flush=True)
    finally: scorer.close()
    results.update(status="completed",policy=policy)
    write_json(a.out/"comparison.json",results)


if __name__=="__main__": main()
