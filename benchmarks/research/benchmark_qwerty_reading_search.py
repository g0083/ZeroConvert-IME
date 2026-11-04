"""Freeze reading-search generation/scores, then tune on validation only."""
import argparse
import json
from pathlib import Path
import time

from benchmark_keyboard_native import NativeScorer
from dataset_isolation import sha256
from qwerty_corrector_policy import write_json
from qwerty_reading_search import ReadingSearch, choose


def metrics(rows, scored, weight, margin):
    tp=fp=total=keep=wait=canary=0
    for row, record in zip(rows,scored):
        selected = choose(record["candidates"],weight,margin)
        repair = row["expected_action"] == "repair"
        total += repair
        if selected:
            correct = repair and selected["reading"] == row["target_reading"]
            tp += bool(correct)
            fp += not correct
            keep += row["expected_action"] == "keep"
            wait += row["expected_action"] == "wait"
            canary += bool(row.get("preservation_canary"))
    return {"reading_correct":tp,"wrong_proposed":fp,"repair_total":total,"keep_proposed":keep,"wait_proposed":wait,
        "preservation_proposed":canary,"case_f0_5":1.25*tp/(1.25*tp+fp+.25*(total-tp)) if total+fp else 0.}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    a.out.mkdir(parents=True)
    model=Path("artifacts/models/zerocon-nano-v4-q4km.gguf")
    tokenizer=Path("artifacts/checkpoints/nano-v4-argon4-merged")
    executable=Path("artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe")
    paths={"val":Path("artifacts/results/corrector-qwerty-state-20261030-r1-val-fixture.json"),
        "test":Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json"),
        "fresh":Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json")}
    plan={"model":str(model),"model_sha256":sha256(model),"executable_sha256":sha256(executable),
        "fixtures":{s:sha256(path) for s,path in paths.items()},"limit":24,"weights":[0,1,2,4,8],"margins":[0,2,5,10,20,30,40,60,80],
        "sources":{s:sha256(s) for s in [__file__,"benchmarks/research/qwerty_reading_search.py"]},
        "scope":"Native Q4 reading selection, not final surface or IME accuracy","results":{}}
    write_json(a.out/"plan.json",plan)
    generator=ReadingSearch(24)
    scorer=NativeScorer(executable,model,Path("ZeroConvertIME-windows/llama_cpu"),tokenizer,a.out/"native.log")
    policy=None
    try:
        for split,path in paths.items():
            rows=json.loads(path.read_text(encoding="utf-8"))["cases"]
            scored=[]
            with (a.out/f"{split}-scores.jsonl").open("x",encoding="utf-8") as stream:
                for i,row in enumerate(rows):
                    start=time.perf_counter()
                    candidates=generator.generate({k:row.get(k) for k in ("observed_keys","input_state")})
                    elapsed=(time.perf_counter()-start)*1000
                    if len(candidates)>1:
                        values=scorer.score(candidates)
                        candidates=[{**c,**s} for c,s in zip(candidates,values)]
                    record={"id":row["id"],"candidates":candidates,"generation_ms":elapsed}
                    scored.append(record)
                    stream.write(json.dumps(record,ensure_ascii=False)+"\n")
                    if i%50==0: stream.flush(); print(split,i,len(rows),flush=True)
            coverage=sum(r["expected_action"]=="repair" and any(c["reading"]==r["target_reading"] for c in s["candidates"]) for r,s in zip(rows,scored))
            if split=="val":
                grid=[{"weight":w,"margin":m,"metrics":metrics(rows,scored,w,m)} for w in plan["weights"] for m in plan["margins"]]
                allowed=[x for x in grid if x["metrics"]["keep_proposed"]<=1 and x["metrics"]["wait_proposed"]==0]
                if not allowed: raise ValueError("No policy meets normal-input cap")
                policy=max(allowed,key=lambda x:x["metrics"]["case_f0_5"])
                write_json(a.out/"selection.json",{"selected":policy,"grid":grid,"scores_sha256":sha256(a.out/"val-scores.jsonl")})
            result={"metrics":metrics(rows,scored,policy["weight"],policy["margin"]),"candidate_reading_coverage":coverage}
            plan["results"][split]=result
            print(split,result,flush=True)
            write_json(a.out/"comparison.json",plan)
    finally: scorer.close()
    plan.update(status="completed",selection=policy)
    write_json(a.out/"comparison.json",plan)


if __name__=="__main__": main()
