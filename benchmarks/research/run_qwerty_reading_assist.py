"""Run the additional reading-search component on an observation (no labels).

Caller supplies any existing correction; this component preserves it. This is
an isolated research CLI, not a registered IME or a full normal-candidate engine.
"""
import argparse
import json
from pathlib import Path

from benchmark_kana_kanji_models import LlamaServerInstance
from benchmark_keyboard_native import NativeScorer
from dataset_isolation import sha256
from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import DICTIONARY, kata
from qwerty_corrector_policy import complete
from qwerty_reading_search import ReadingSearch, choose


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--keys",required=True)
    p.add_argument("--context",default="")
    p.add_argument("--state",choices=("append","backspace","unknown"),default="append")
    p.add_argument("--baseline-surface")
    p.add_argument("--selection-dir",type=Path,default=Path("artifacts/results/corrector-qwerty-reading-surface-20261030-r1"))
    p.add_argument("--search-dir",type=Path,default=Path("artifacts/results/corrector-qwerty-reading-search-20261030-r1"))
    p.add_argument("--log-prefix",type=Path,required=True)
    a=p.parse_args()
    if a.baseline_surface:
        print(json.dumps({"surface":a.baseline_surface,"source":"existing-correction-preserved"},ensure_ascii=False)); return
    meta=json.loads((a.selection_dir/"comparison.json").read_text(encoding="utf-8"))
    if meta.get("status")!="completed": raise ValueError("Selection not complete")
    policy=meta["selection"]
    if sha256(a.search_dir/"comparison.json")!=meta["sources"]["search"]:
        raise ValueError("Selection and search results differ")
    search_meta=json.loads((a.search_dir/"comparison.json").read_text(encoding="utf-8"))
    model=Path(search_meta["model"])
    executable=Path("artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe")
    normal=Path("artifacts/models/zerocon/zerocon-model-standard.gguf")
    if sha256(model)!=search_meta["model_sha256"] or sha256(executable)!=search_meta["executable_sha256"] or sha256(normal)!=meta["sources"]["normal"] or sha256(DICTIONARY)!=meta["sources"]["dictionary"]:
        raise ValueError("Selected dependencies changed")
    search=ReadingSearch(24)
    candidates=search.generate({"observed_keys":a.keys,"input_state":a.state})
    if len(candidates)<2 or policy["weight"] is None:
        print(json.dumps({"surface":None,"reason":"no-active-search"})); return
    a.log_prefix.parent.mkdir(parents=True,exist_ok=True)
    scorer=NativeScorer(executable,model,Path("ZeroConvertIME-windows/llama_cpu"),Path("artifacts/checkpoints/nano-v4-argon4-merged"),a.log_prefix.with_suffix(".native.log"))
    try:
        scores=scorer.score(candidates)
        candidates=[{**c,**s} for c,s in zip(candidates,scores)]
    finally: scorer.close()
    candidate=choose(candidates,policy["weight"],policy["margin"])
    if not candidate:
        print(json.dumps({"surface":None,"reason":"insufficient-margin"})); return
    server=LlamaServerInstance(str(normal.resolve()),8580,0,"cpu")
    prompt=("\uee02"+a.context if a.context else "")+"\uee00"+kata(candidate["reading"])+"\uee01"
    try:
        server.start(); response=complete(server,prompt,256)
    finally: server.stop()
    surface=response.get("content","").strip()
    safe=bool(surface) and not response.get("stopped_limit") and not response.get("truncated") and response.get("tokens_predicted",0)<256 and response.get("stop_type")!="limit" and not any(c in surface for c in "\n\r\uee00\uee01\uee02") and ReadingVerifier(DICTIONARY).matches(surface,candidate["reading"])
    print(json.dumps({"surface":surface if safe else None,"reading":candidate["reading"],"witness_keys":candidate["keys"],
        "auto_apply":False,"reading_verified":safe,"selection_sha256":sha256(a.selection_dir/"selection.json")},ensure_ascii=False))


if __name__=="__main__": main()
