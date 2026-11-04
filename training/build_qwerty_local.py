"""Export matched corrector runs; calibration uses each run's actual contract."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from dataset_isolation import load_jsonl, sha256
from qwerty_local_eval import input_prompt
from qwerty_corrector_policy import write_json


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name",required=True)
    a=p.parse_args()
    if Path(a.run_name).name!=a.run_name: raise ValueError("Expected one run name")
    adapter=Path("artifacts/checkpoints")/(a.run_name+"-lora")
    saved=json.loads((adapter/"run-manifest.json").read_text(encoding="utf-8"))
    provenance=json.loads((adapter/"dataset-provenance.json").read_text(encoding="utf-8"))
    if not provenance["training_completed"]: raise ValueError("Training incomplete")
    base=Path(saved["arguments"]["base_model"])
    for name,digest in saved["base"].items():
        if sha256(base/name)!=digest: raise ValueError("Parent changed")
    for key in ("train","validation"):
        if sha256(provenance[key]["path"])!=provenance[key]["sha256"]: raise ValueError("Data changed")
    mode=saved["arguments"]["mode"]
    merged=Path("artifacts/checkpoints")/(a.run_name+"-merged")
    f16=Path("artifacts/models")/(a.run_name+"-f16.gguf")
    q4=Path("artifacts/models")/(a.run_name+"-q4km.gguf")
    matrix=Path("artifacts/models")/(a.run_name+".imatrix")
    directory=Path("artifacts/datasets")/(a.run_name+"-imatrix")
    if any(p.exists() for p in (merged,f16,q4,matrix,directory)): raise FileExistsError(a.run_name)
    rows=load_jsonl(provenance["train"]["path"])
    rows.sort(key=lambda r:hashlib.sha256(r["id"].encode()).hexdigest())
    directory.mkdir(parents=True)
    corpus=directory/"calibration.txt"
    corpus.write_text("\n".join(input_prompt(r)+(r["local_completion"] if mode=="local" else r["completion"])+"</s>" for r in rows)+"\n",encoding="utf-8")
    runtime=Path("ZeroConvertIME-windows/llama_vulkan")
    converter=Path("android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py")
    commands=[[sys.executable,"scripts/run.py","training/export_typo_corrector.py","--base_model",str(base),"--lora_dir",str(adapter),"--output_dir",str(merged)],
        [sys.executable,"scripts/run.py",str(converter),str(merged),"--outfile",str(f16),"--outtype","f16"],
        [str(runtime/"llama-imatrix.exe"),"-m",str(f16),"-f",str(corpus),"-o",str(matrix),"-ngl","0","-c","512","--chunks","64","-t","4"],
        [str(runtime/"llama-quantize.exe"),"--imatrix",str(matrix),str(f16),str(q4),"Q4_K_M"]]
    manifest={"mode":mode,"training_manifest_sha256":sha256(adapter/"run-manifest.json"),"calibration_sha256":sha256(corpus),"commands":commands,"status":"exporting"}
    write_json(directory/"manifest.json",manifest)
    for i,command in enumerate(commands):
        print("Export",i+1,flush=True)
        with (Path("artifacts/logs")/f"{a.run_name}-export-{i}.log").open("w",encoding="utf-8") as stream:
            subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,creationflags=subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
    manifest.update(status="completed",outputs={str(p):{"sha256":sha256(p),"bytes":p.stat().st_size} for p in (f16,q4)},imatrix_sha256=sha256(matrix))
    write_json(directory/"manifest.json",manifest)


if __name__=="__main__": main()
