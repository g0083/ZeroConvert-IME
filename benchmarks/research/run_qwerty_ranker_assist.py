"""Manual candidate preview using a locked Standard ranking model and normal converter."""
import argparse
import json
from pathlib import Path
import time

from benchmark_kana_kanji_models import LlamaServerInstance
from dataset_isolation import sha256
from evaluate_qwerty_ranker import RankingScorer
from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import DICTIONARY, kata
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import complete
from qwerty_ranker import select
from qwerty_reading_search import ReadingSearch
from qwerty_trace import generate_candidates


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--keys",required=True); p.add_argument("--context",default="")
    p.add_argument("--state",choices=("append","backspace","unknown"),default="append")
    p.add_argument("--baseline-surface"); p.add_argument("--lock",type=Path,required=True)
    p.add_argument("--tokenizer",type=Path,required=True); p.add_argument("--log-prefix",type=Path,required=True)
    p.add_argument("--normal-model",type=Path,default=Path("artifacts/models/standard-v4-nano-transfer-q8_0.gguf"))
    p.add_argument("--port",type=int,default=8594)
    a=p.parse_args(); lock=json.loads(a.lock.read_text(encoding="utf-8")); identity=lock["identity"]
    model=Path(identity["model"]); executable=Path("artifacts/build/qwerty-ranker-20261103-r1/qwerty-ranker-score.exe")
    if sha256(model)!=identity["model_sha256"] or sha256(executable)!=identity["executable_sha256"] or sha256("training/qwerty_ranker.py")!=identity["contract_sha256"]:
        raise ValueError("Locked dependencies changed")
    tokenizer_hashes={p.name:sha256(p) for p in a.tokenizer.iterdir() if p.suffix in (".json",".txt")}
    if tokenizer_hashes!=identity["tokenizer"]: raise ValueError("Tokenizer changed")
    a.log_prefix.parent.mkdir(parents=True,exist_ok=True)
    native=NativeQwerty(); reading=native.decode(a.keys)
    obs={"context":a.context,"observed_keys":a.keys,"native_observed":reading,"input_state":a.state,"cursor_key_offset":len(a.keys)}
    search=ReadingSearch(24); start=time.perf_counter(); candidates=generate_candidates(search,obs)
    generation_ms=(time.perf_counter()-start)*1000
    scorer=RankingScorer(executable,model,Path("ZeroConvertIME-windows/llama_cpu"),a.tokenizer,a.log_prefix.with_suffix(".native.log"))
    try:
        start=time.perf_counter(); scores=scorer.score_observation(obs,candidates); score_ms=(time.perf_counter()-start)*1000
    finally: scorer.close()
    index=select(candidates,scores,lock["policy"]["margin"])
    result={"normal_candidate":a.baseline_surface,"suggested_surface":None,"suggested_reading":None,"auto_apply":False,
        "policy_lock_sha256":sha256(a.lock),"candidates":candidates,"scores":scores,
        "generation_ms":generation_ms,"score_ms":score_ms,"scope":"Standalone manual preview; not TSF/GUI integration"}
    if index is not None:
        chosen=candidates[index]
        normal=LlamaServerInstance(str(a.normal_model.resolve()),a.port,0,"cpu")
        prompt=("\uee02"+a.context if a.context else "")+"\uee00"+kata(chosen["reading"])+"\uee01"
        try:
            normal.start(); start=time.perf_counter(); response=complete(normal,prompt,128)
            result["surface_ms"]=(time.perf_counter()-start)*1000
        finally: normal.stop()
        surface=response.get("content","").strip()
        valid=bool(surface) and not response.get("stopped_limit") and not response.get("truncated") and response.get("stop_type")!="limit" and response.get("tokens_predicted",0)<128 and not any(c in surface for c in "\n\r\uee00\uee01\uee02") and ReadingVerifier(DICTIONARY).matches(surface,chosen["reading"])
        result.update(suggested_surface=surface if valid else None,suggested_reading=chosen["reading"],
            witness_keys=chosen["keys"],reading_verified=valid,normal_model_sha256=sha256(a.normal_model),
            dictionary_sha256=sha256(DICTIONARY),response=response)
    a.log_prefix.with_suffix(".json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:v for k,v in result.items() if k not in ("candidates","scores","response")},ensure_ascii=False),flush=True)


if __name__=="__main__": main()
