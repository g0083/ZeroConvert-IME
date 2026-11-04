"""Post-selection diagnostic only: merged FP32 / GGUF F16 / Q4 local output."""
import argparse
import hashlib
import json
from pathlib import Path
import torch
from transformers import AutoModelForCausalLM,AutoTokenizer
from benchmark_kana_kanji_models import LlamaServerInstance
from evaluate_v4_repair_comparison import ReadingVerifier
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import complete,DICTIONARY,write_json
from qwerty_local_eval import input_prompt,interpret,metrics
from train_qwerty_local import generate_hf


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name",required=True); p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    if a.out.exists():raise FileExistsError(a.out)
    fixture=Path("artifacts/checkpoints")/(a.run_name+"-lora")/"validation-fixture.json"
    rows=json.loads(fixture.read_text(encoding="utf-8"))["cases"]
    rows=sorted(rows,key=lambda r:hashlib.sha256(r["id"].encode()).hexdigest())[:128]
    native=NativeQwerty();verifier=ReadingVerifier(DICTIONARY)
    merged=Path("artifacts/checkpoints")/(a.run_name+"-merged")
    tokenizer=AutoTokenizer.from_pretrained(merged,local_files_only=True)
    torch.set_num_threads(4)
    model=AutoModelForCausalLM.from_pretrained(merged,dtype=torch.float32,local_files_only=True).to("cuda" if torch.cuda.is_available() else "cpu")
    outputs=generate_hf(model,tokenizer,rows,"local")
    results={"merged-fp32":{"outputs":outputs,"metrics":metrics(rows,[interpret(v["text"],r,"local",native,verifier) for r,v in zip(rows,outputs)],verifier)}}
    del model; torch.cuda.empty_cache()
    for quant in ("f16","q4km"):
        server=LlamaServerInstance(str((Path("artifacts/models")/(a.run_name+f"-{quant}.gguf")).resolve()),8592,0,"cpu")
        values=[]
        try:
            server.start()
            for row in rows:
                response=complete(server,input_prompt(row),48)
                text=response.get("content","") if response.get("stopped_eos") or response.get("stop_type")=="eos" else ""
                values.append({"text":text,"response":response})
        finally:server.stop()
        results[quant]={"outputs":values,"metrics":metrics(rows,[interpret(v["text"],r,"local",native,verifier) for r,v in zip(rows,values)],verifier)}
    write_json(a.out,{"scope":"Diagnostic on 128 hash-ordered validation cases after model selection; never used to change weights/selection",
        "ids":[r["id"] for r in rows],"results":results})
    print({k:v["metrics"] for k,v in results.items()})


if __name__=="__main__":main()
