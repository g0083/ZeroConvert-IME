"""HF vs F16 native masked-score parity on development observations only."""
import argparse
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

from dataset_isolation import load_jsonl, sha256
from evaluate_qwerty_ranker import RankingScorer
from qwerty_corrector_policy import write_json
from qwerty_ranker import encode_candidates
from train_qwerty_ranker import hf_records


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("--run-name",required=True)
    p.add_argument("--dataset",type=Path,required=True); p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    base=Path("artifacts/checkpoints")/(a.run_name+"-merged")
    model_path=Path("artifacts/models")/(a.run_name+"-f16.gguf")
    tokenizer=AutoTokenizer.from_pretrained(base,local_files_only=True)
    active=[r for r in load_jsonl(a.dataset/"val.jsonl") if len(r["candidates"])>1]
    active.sort(key=lambda r:max(len(v['input_ids']) for v in encode_candidates(tokenizer,r,r['candidates'])))
    rows=active[:4]+active[-4:]
    torch.set_num_threads(4)
    model=AutoModelForCausalLM.from_pretrained(base,local_files_only=True,dtype=torch.float32).to("cuda" if torch.cuda.is_available() else "cpu")
    reference=hf_records(model,tokenizer,rows)
    scorer=RankingScorer(Path("artifacts/build/qwerty-ranker-20261103-r1/qwerty-ranker-score.exe"),model_path,
        Path("ZeroConvertIME-windows/llama_cpu"),base,a.out.with_suffix(".log"))
    cases=[]
    try:
        for row,ref in zip(rows,reference):
            actual=scorer.score_observation(row,row['candidates'])
            error=max(abs(a-b) for a,b in zip(actual,ref['scores']))
            cases.append({"id":row['id'],"max_abs_mean_logp_error":error,
                "longest_sequence":max(len(v['input_ids']) for v in encode_candidates(tokenizer,row,row['candidates'])),
                "hf_scores":ref['scores'],"native_scores":actual})
    finally: scorer.close()
    passed=max(c['max_abs_mean_logp_error'] for c in cases)<.1
    write_json(a.out,{"status":"PASS" if passed else "FAIL","tolerance":.1,"cases":cases,
        "scope":"F16 vs HF BF16 arithmetic; eight development observations, not quality evaluation",
        "validation_sha256":sha256(a.dataset/"val.jsonl"),"model_sha256":sha256(model_path)})
    if not passed: raise ValueError("Masked native score parity failed")
    print("PASS",max(c['max_abs_mean_logp_error'] for c in cases),flush=True)


if __name__=="__main__": main()
