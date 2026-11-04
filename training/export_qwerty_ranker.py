"""Merge completed ranking adapter and export F16/Q8, retaining input hashes."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from dataset_isolation import sha256
from qwerty_corrector_policy import write_json


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("--run-name",required=True); a=p.parse_args()
    if Path(a.run_name).name!=a.run_name: raise ValueError("Expected one name")
    adapter=Path("artifacts/checkpoints")/(a.run_name+"-lora")
    manifest=json.loads((adapter/"run-manifest.json").read_text(encoding="utf-8"))
    provenance=json.loads((adapter/"dataset-provenance.json").read_text(encoding="utf-8"))
    if manifest["status"]!="completed" or not provenance["training_completed"]: raise ValueError("Incomplete training")
    if sha256(adapter/"adapter_model.safetensors")!=manifest["adapter_sha256"]: raise ValueError("Adapter changed")
    base=Path(manifest["arguments"]["base"])
    for name,digest in manifest["base"].items():
        if sha256(base/name)!=digest: raise ValueError("Parent changed")
    for key in ("train","validation"):
        if sha256(provenance[key]["path"])!=provenance[key]["sha256"]: raise ValueError("Dataset changed")
    merged=Path("artifacts/checkpoints")/(a.run_name+"-merged")
    f16=Path("artifacts/models")/(a.run_name+"-f16.gguf"); q8=f16.with_name(a.run_name+"-q8.gguf")
    card=q8.with_suffix(".json")
    if any(p.exists() for p in (merged,f16,q8,card)): raise FileExistsError(a.run_name)
    commands=[[sys.executable,"scripts/run.py","training/export_typo_corrector.py","--base_model",str(base),"--lora_dir",str(adapter),"--output_dir",str(merged)],
        [sys.executable,"scripts/run.py","android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py",str(merged),"--outfile",str(f16),"--outtype","f16"],
        ["ZeroConvertIME-windows/llama_vulkan/llama-quantize.exe",str(f16),str(q8),"Q8_0"]]
    for i,command in enumerate(commands):
        with (Path("artifacts/logs")/f"{a.run_name}-export-{i}.log").open("x",encoding="utf-8") as stream:
            subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True)
    write_json(card,{"status":"research-only","training_manifest_sha256":sha256(adapter/"run-manifest.json"),
        "contract":"qwerty-ranker-mean-reading-eos-v1","outputs":{str(p):{"sha256":sha256(p),"bytes":p.stat().st_size} for p in (f16,q8)},"commands":commands})
    print("Exported",q8,flush=True)


if __name__=="__main__": main()
