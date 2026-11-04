"""Matched standalone full/local corrector GGUF evaluation (manual proposals).

No reading-search/prior model at inference. Local E output is applied once,
decoded natively and converted by the existing normal model.
"""
import argparse
import json
from pathlib import Path
import re
import time

from benchmark_kana_kanji_models import LlamaServerInstance
from dataset_isolation import sha256
from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import kata
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import complete, DICTIONARY, summary, write_json
from qwerty_local_eval import input_prompt, interpret, metrics
from qwerty_channel_filter import ChannelFilter


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--full-run",required=True)
    p.add_argument("--local-run",required=True)
    p.add_argument("--additional-local-run",action="append",default=[])
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    a.out.mkdir(parents=True)
    runs={"full":a.full_run,"local":a.local_run,**{f"local_{i+2}":name for i,name in enumerate(a.additional_local_run)}}
    fixtures={m:Path("artifacts/checkpoints")/(name+"-lora")/"validation-fixture.json" for m,name in runs.items()}
    if len({sha256(p) for p in fixtures.values()})!=1: raise ValueError("Validation inputs differ")
    paths={"val":fixtures["full"],"test":Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json"),
        "fresh":Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json")}
    models={m:Path("artifacts/models")/(name+"-q4km.gguf") for m,name in runs.items()}
    normal_path=Path("artifacts/models/zerocon/zerocon-model-standard.gguf")
    normal=LlamaServerInstance(str(normal_path.resolve()),8591,0,"cpu")
    native=NativeQwerty(); verifier=ReadingVerifier(DICTIONARY)
    channel=ChannelFilter()
    report={"models":{m:{"path":str(p),"sha256":sha256(p),"run":runs[m]} for m,p in models.items()},
        "sources":{str(p):sha256(p) for p in [Path(__file__),Path("training/qwerty_local_contract.py"),Path("benchmarks/research/qwerty_local_eval.py"),normal_path,DICTIONARY,*paths.values()]},
        "scope":"Standalone trained model comparison; no prior/search model. Manual-only. Shared end-pending/backspace guard in final surface metrics; raw metrics unguarded.","results":{}}
    write_json(a.out/"plan.json",report)
    try:
        normal.start()
        for split,path in paths.items():
            rows=json.loads(path.read_text(encoding="utf-8"))["cases"]
            report["results"][split]={}
            for label,model in models.items():
                mode="full" if label=="full" else "local"
                server=LlamaServerInstance(str(model.resolve()),8590,0,"cpu")
                predictions=[]; cases=[]; physical_cases=[]; requests=[]; normal_calls=[]; timings=[]; physical_timings=[]
                try:
                    server.start()
                    complete(server,input_prompt(rows[0]),48 if mode=="local" else 128)
                    for i,row in enumerate(rows):
                        prompt=input_prompt(row)
                        started=time.perf_counter()
                        response=complete(server,prompt,48 if mode=="local" else 128)
                        ended=bool(response.get("stopped_eos") or response.get("stop_type")=="eos")
                        pred=interpret(response.get("content","") if ended else "",row,mode,native,verifier)
                        predictions.append(pred)
                        surface=None
                        guard=row.get("input_state")=="backspace" or bool(re.search(r"[a-zA-Z]$",row["native_observed"]))
                        if pred["valid"] and pred["action"]=="E" and not guard:
                            if mode=="full": surface=pred["surface"]
                            else:
                                normal_prompt=("\uee02"+row.get("context","") if row.get("context") else "")+"\uee00"+kata(pred["reading"])+"\uee01"
                                result=complete(normal,normal_prompt,256)
                                text=result.get("content","")
                                safe=bool(text) and not result.get("stopped_limit") and not result.get("truncated") and result.get("stop_type")!="limit" and result.get("tokens_predicted",0)<256 and not any(c in text for c in "\n\r\uee00\uee01\uee02") and verifier.matches(text,pred["reading"])
                                normal_calls.append({"id":row["id"],"prompt":normal_prompt,"response":result,"reading_verified":safe})
                                if safe: surface=text
                        elapsed=(time.perf_counter()-started)*1000; timings.append(elapsed)
                        verified=surface
                        witness=None
                        if surface is not None and mode=="full":
                            witness=channel.witness(row,surface)
                            if not witness: verified=None
                        checked_elapsed=(time.perf_counter()-started)*1000; physical_timings.append(checked_elapsed)
                        requests.append({"id":row["id"],"prompt":prompt,"response":response,"prediction":pred,"guarded":guard})
                        cases.append({"id":row["id"],"correction":surface,"auto_apply":False,"crashed":False,"elapsed_ms":elapsed})
                        physical_cases.append({"id":row["id"],"correction":verified,"auto_apply":False,"crashed":False,"elapsed_ms":checked_elapsed,"witness":witness or (pred if mode=="local" and verified else None)})
                        if i%100==0: print(split,label,i,len(rows),flush=True)
                finally: server.stop()
                values=summary({"cases":cases},rows)
                times=sorted(timings)
                values.update(p50_ms=times[len(times)//2],p95_ms=times[min(len(times)-1,int(len(times)*.95))])
                physical=summary({"cases":physical_cases},rows)
                physical_times=sorted(physical_timings)
                physical.update(p50_ms=physical_times[len(physical_times)//2],p95_ms=physical_times[min(len(physical_times)-1,int(len(physical_times)*.95))])
                raw=metrics(rows,predictions,verifier)
                result={"summary":values,"physical_summary":physical,"raw_metrics":raw,"requests":requests,"normal_calls":normal_calls,"report":{"cases":cases},"physical_report":{"cases":physical_cases},
                    "model_sha256":sha256(model),"fixture_sha256":sha256(path),"timing_scope":"CPU model request + parsing/native validation + optional normal conversion; startup/normal candidates/TSF/UI excluded"}
                write_json(a.out/f"{split}-{label}.json",result)
                report["results"][split][label]={"surface":values,"physical":physical,"raw":raw}
                print(split,label,"basic",values,"physical",physical,"raw",raw,flush=True)
                write_json(a.out/"comparison.json",report)
            if split=="val":
                eligible=[m for m,v in report["results"][split].items() if v["physical"].get("keep_proposed",0)<=1 and v["physical"].get("wait_proposed",0)==0 and v["physical"].get("preservation_proposed",0)<=1]
                report["validation_selected"]=max(eligible,key=lambda m:report["results"][split][m]["physical"]["case_f0_5"]) if eligible else None
                write_json(a.out/"selection.json",{"selected":report["validation_selected"],"validation":report["results"][split]})
    finally: normal.stop()
    report["status"]="completed"; write_json(a.out/"comparison.json",report)


if __name__=="__main__": main()
