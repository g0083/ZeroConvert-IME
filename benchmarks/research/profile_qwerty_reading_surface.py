"""Profile the additional no-baseline-candidate path, not overall IME latency."""
import argparse
import hashlib
import json
from pathlib import Path
import time

from benchmark_kana_kanji_models import LlamaServerInstance
from benchmark_keyboard_native import NativeScorer
from dataset_isolation import sha256
from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import kata
from qwerty_corrector_policy import complete, DICTIONARY, write_json
from qwerty_reading_search import ReadingSearch, choose


def percentiles(values):
    values=sorted(values)
    return {"p50_ms":values[len(values)//2],"p95_ms":values[min(len(values)-1,int(len(values)*.95))]}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--selection-dir",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    selected=json.loads((a.selection_dir/"selection.json").read_text(encoding="utf-8"))["selected"]
    fixture=json.loads(Path("artifacts/results/corrector-qwerty-state-20261030-r1-test-fixture.json").read_text(encoding="utf-8"))
    baseline=json.loads(Path("artifacts/results/corrector-qwerty-state-20261030-r1-comparison/test-previous-qwerty-state-channel.json").read_text(encoding="utf-8"))["report"]["cases"]
    inputs=[r for r,b in zip(fixture["cases"],baseline) if b["correction"] is None and r.get("observed_keys") and r.get("input_state")=="append"]
    inputs=sorted(inputs,key=lambda r:hashlib.sha256(r["id"].encode()).hexdigest())[:24]
    search=ReadingSearch(24)
    verifier=ReadingVerifier(DICTIONARY)
    model=Path("artifacts/models/zerocon-nano-v4-q4km.gguf")
    scorer=NativeScorer(Path("artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe"),model,
        Path("ZeroConvertIME-windows/llama_cpu"),Path("artifacts/checkpoints/nano-v4-argon4-merged"),a.out.with_suffix(".native.log"))
    server=LlamaServerInstance(str(Path("artifacts/models/zerocon/zerocon-model-standard.gguf").resolve()),8580,0,"cpu")
    samples=[]
    try:
        server.start()
        scorer.score([{"reading":"かいぎ"}])
        for repeat in range(3):
            for row in inputs:
                start=time.perf_counter()
                candidates=search.generate({k:row.get(k) for k in ("observed_keys","input_state")})
                generated=time.perf_counter()
                if len(candidates)>1:
                    values=scorer.score(candidates)
                    candidates=[{**c,**s} for c,s in zip(candidates,values)]
                scored=time.perf_counter()
                candidate=choose(candidates,selected["weight"],selected["margin"])
                valid=False
                if candidate:
                    prompt=("\uee02"+row["context"] if row["context"] else "")+"\uee00"+kata(candidate["reading"])+"\uee01"
                    response=complete(server,prompt,256)
                    surface=response.get("content","").strip()
                    valid=bool(surface) and verifier.matches(surface,candidate["reading"])
                end=time.perf_counter()
                samples.append({"id":row["id"],"repeat":repeat,"candidates":len(candidates),"surface_reading_verified":valid,
                    "generation_ms":(generated-start)*1000,"score_ms":(scored-generated)*1000,
                    "conversion_ms":(end-scored)*1000,"total_ms":(end-start)*1000})
    finally:
        scorer.close(); server.stop()
    write_json(a.out,{"scope":"Additional path only: Python generation + CPU native Nano Q4 + CPU normal conversion; startup and baseline/TSF/display excluded",
        "selection_sha256":sha256(a.selection_dir/"selection.json"),"model_sha256":sha256(model),"sample_selection":"24 case IDs in hash order with absent baseline, raw available, append state; labels unused",
        "per_repeat":{str(i):percentiles([r["total_ms"] for r in samples if r["repeat"]==i]) for i in range(3)},"samples":samples})
    print(a.out)


if __name__=="__main__": main()
