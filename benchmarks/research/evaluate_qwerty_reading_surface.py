"""Convert physically verified readings and select additive proposals on val.

Baseline suggestions stay intact. New reading-search proposals are manual only.
Gold is used solely by summary/validation selection, never conversion prompts.
"""
import argparse
from copy import deepcopy
import json
from pathlib import Path

from benchmark_kana_kanji_models import LlamaServerInstance
from dataset_isolation import load_jsonl, sha256
from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import kata
from qwerty_corrector_policy import complete, DICTIONARY, summary, write_json
from qwerty_reading_search import choose


def combine(baseline, rows, scores, weight, margin, converted):
    result=deepcopy(baseline)
    for item,row,record in zip(result["report"]["cases"],rows,scores):
        if item["id"]!=row["id"] or row["id"]!=record["id"]: raise ValueError("Case order changed")
        if item["correction"] is not None or weight is None: continue
        candidate=choose(record["candidates"],weight,margin)
        if candidate:
            surface=converted.get((row["id"],candidate["reading"]))
            if surface:
                item.update(correction=surface, auto_apply=False, reading_search_added=True, reading_search_candidate=candidate)
    result["summary"]=summary(result["report"],rows)
    result["scope"]="Offline additive reading-search + independent normal conversion, not TSF/IME; added proposals manual only"
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--search-dir",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    search_meta=json.loads((a.search_dir/"comparison.json").read_text(encoding="utf-8"))
    if search_meta.get("status")!="completed": raise ValueError("Search incomplete")
    a.out.mkdir(parents=True)
    normal=Path("artifacts/models/zerocon/zerocon-model-standard.gguf")
    server=LlamaServerInstance(str(normal.resolve()),8580,0,"cpu")
    verifier=ReadingVerifier(DICTIONARY)
    base_dir=Path("artifacts/results/corrector-qwerty-state-20261030-r1-comparison")
    paths={"val":Path("artifacts/results/corrector-qwerty-state-20261030-r1-val-fixture.json"),
        "test":Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json"),
        "fresh":Path("artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json")}
    weights=search_meta["weights"]
    margins=search_meta["margins"]
    meta={"scope":"Surface exact/acceptable scoring; model conversion is independent; no gold in requests",
        "sources":{"search":sha256(a.search_dir/"comparison.json"),"script":sha256(__file__),"normal":sha256(normal),"dictionary":sha256(DICTIONARY)},
        "weights":weights,"margins":margins,"results":{}}
    write_json(a.out/"plan.json",meta)
    selection=None
    try:
        server.start()
        for split,path in paths.items():
            fixture=json.loads(path.read_text(encoding="utf-8")); rows=fixture["cases"]
            baseline=json.loads((base_dir/f"{split}-previous-qwerty-state-channel.json").read_text(encoding="utf-8"))
            scores=load_jsonl(a.search_dir/f"{split}-scores.jsonl")
            converted={}; calls=[]
            for i,(row,record,prior) in enumerate(zip(rows,scores,baseline["report"]["cases"])):
                if prior["correction"] is not None: continue
                for w in weights if split=="val" else [selection["weight"]]:
                    if w is None: continue
                    candidate=choose(record["candidates"],w,0 if split=="val" else selection["margin"])
                    if not candidate: continue
                    key=(row["id"],candidate["reading"])
                    if key in converted: continue
                    prompt=("\uee02"+row["context"] if row["context"] else "")+"\uee00"+kata(candidate["reading"])+"\uee01"
                    response=complete(server,prompt,256)
                    surface=response.get("content","").strip()
                    safe=bool(surface) and not response.get("stopped_limit") and not response.get("truncated") and response.get("tokens_predicted",0)<256 and response.get("stop_type")!="limit" and not any(c in surface for c in "\n\r\uee00\uee01\uee02") and verifier.matches(surface,candidate["reading"])
                    converted[key]=surface if safe else None
                    calls.append({"id":row["id"],"candidate":candidate,"prompt":prompt,"response":response,"reading_verified":safe})
                if i%100==0: print(split,"convert",i,len(rows),flush=True)
            write_json(a.out/f"{split}-conversions.json",{"calls":calls,"fixture_sha256":sha256(path),"score_sha256":sha256(a.search_dir/f"{split}-scores.jsonl")})
            if split=="val":
                grid=[{"weight":None,"margin":None,"summary":baseline["summary"]}]
                for w in weights:
                    for m in margins:
                        result=combine(baseline,rows,scores,w,m,converted)
                        grid.append({"weight":w,"margin":m,"summary":result["summary"]})
                cap=baseline["summary"]
                allowed=[g for g in grid if all(g["summary"].get(k,0)<=cap.get(k,0) for k in ("keep_proposed","wait_proposed","wrong_auto_applied","preservation_proposed"))]
                selection=max(allowed,key=lambda g:g["summary"]["case_f0_5"])
                write_json(a.out/"selection.json",{"selected":selection,"grid":grid,"fixture_sha256":sha256(path)})
            result=combine(baseline,rows,scores,selection["weight"],selection["margin"],converted)
            write_json(a.out/f"{split}-selected.json",result)
            meta["results"][split]={"baseline":baseline["summary"],"selected":result["summary"],"conversion_calls":len(calls)}
            print(split,meta["results"][split],flush=True)
            write_json(a.out/"comparison.json",meta)
    finally: server.stop()
    meta.update(status="completed",selection=selection)
    write_json(a.out/"comparison.json",meta)


if __name__=="__main__": main()
