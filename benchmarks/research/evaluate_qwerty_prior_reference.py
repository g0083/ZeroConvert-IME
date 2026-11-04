"""Same candidate sets, frozen Nano prior policy: a system reference, not a base-model ablation."""
import argparse
import json
from pathlib import Path
import time

from benchmark_keyboard_native import NativeScorer
from dataset_isolation import load_jsonl, sha256
from evaluate_qwerty_ranker import timing
from qwerty_corrector_policy import write_json
from qwerty_ranker import metrics


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--lock",type=Path,required=True); p.add_argument("--prospective",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    lock=json.loads(a.lock.read_text(encoding="utf-8"))
    for s in ("dev","test"):
        if sha256(a.prospective/f"{s}.jsonl")!=lock["identity"]["files"][s]: raise ValueError("Wrong candidate set")
    prior_path=Path("artifacts/results/corrector-qwerty-reading-search-20261030-r1/comparison.json")
    prior=json.loads(prior_path.read_text(encoding="utf-8"))
    model=Path(prior["model"]); executable=Path("artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe")
    if prior["status"]!="completed" or sha256(model)!=prior["model_sha256"] or sha256(executable)!=prior["executable_sha256"]:
        raise ValueError("Prior dependencies changed or incomplete")
    policy=prior["selection"]; a.out.mkdir(parents=True)
    result={"scope":__doc__,"policy":policy,"prior_manifest_sha256":sha256(prior_path),"status":"evaluating",
        "reference_lock_sha256":sha256(a.lock),"model_sha256":sha256(model)}
    scorer=NativeScorer(executable,model,Path("ZeroConvertIME-windows/llama_cpu"),Path("artifacts/checkpoints/nano-v4-argon4-merged"),a.out/"native.log")
    try:
        warm=next(r for r in load_jsonl(a.prospective/"dev.jsonl") if len(r["candidates"])>1)
        scorer.score(warm["candidates"])
        for split in ("dev","test"):
            rows=load_jsonl(a.prospective/f"{split}.jsonl"); records=[]
            with (a.out/f"{split}-scores.jsonl").open("x",encoding="utf-8") as stream:
                for i,row in enumerate(rows):
                    start=time.perf_counter(); candidates=row["candidates"]
                    values=scorer.score(candidates) if len(candidates)>1 else [{"lm_sum":0.}]*len(candidates)
                    scores=[c["dictionary"]+policy["weight"]*v["lm_sum"] for c,v in zip(candidates,values)]
                    record={"id":row["id"],"candidates":candidates,"scores":scores,"score_ms":(time.perf_counter()-start)*1000}
                    records.append(record); stream.write(json.dumps(record,ensure_ascii=False)+"\n")
                    if i%50==0: stream.flush(); print(split,i,len(rows),flush=True)
            result[split]={"metrics":metrics(rows,records,policy["margin"]),"timing":timing(records)}
            write_json(a.out/"comparison.json",result)
    finally: scorer.close()
    result["status"]="completed"; write_json(a.out/"comparison.json",result)
    print(json.dumps({s:result[s] for s in ("dev","test")},ensure_ascii=False),flush=True)


if __name__=="__main__": main()
