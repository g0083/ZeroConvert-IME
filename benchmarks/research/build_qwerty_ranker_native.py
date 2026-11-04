"""Build a research-only 256-token variant of the bounded native scoring probe.

The production source is read, never modified. Only the protocol length cap is
expanded; each request still has <=32 sequences and <=4096 total input tokens.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

from dataset_isolation import sha256


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    original=Path("windows/native/keyboard-prior/main.cpp")
    text=original.read_text(encoding="utf-8")
    needle="n < 2 || n > 128 || prefix < 1 ||"
    if text.count(needle)!=1: raise ValueError("Protocol source changed; review length cap")
    include=Path("artifacts/build/keyboard-hybrid-20261027/include")
    spec=importlib.util.spec_from_file_location("native_build", "windows/native/keyboard-prior/build.py")
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    for name,(_,expected) in module.HEADERS.items():
        if sha256(include/name)!=expected: raise ValueError("Pinned header changed: "+name)
    runtime=Path("ZeroConvertIME-windows/llama_cpu")
    a.out.mkdir(parents=True)
    source=a.out/"qwerty_ranker_score.cpp"
    source.write_text(text.replace(needle,"n < 2 || n > 256 || prefix < 1 ||"),encoding="utf-8")
    executable=a.out/"qwerty-ranker-score.exe"
    command=[shutil.which("clang++"),"-std=c++17","-O2","-Wno-deprecated-declarations","-DLLAMA_SHARED",
        "-I",str(include),"-I","windows/native/keyboard-prior",str(source),str(runtime/"llama.lib"),"-o",str(executable)]
    subprocess.run(command,check=True,creationflags=subprocess.CREATE_NO_WINDOW)
    (a.out/"build.json").write_text(json.dumps({"command":command,"original_sha256":sha256(original),
        "generated_sha256":sha256(source),"executable_sha256":sha256(executable),
        "channel_sha256":sha256("windows/native/keyboard-prior/channel.hpp"),
        "headers":{name:sha256(include/name) for name in module.HEADERS},
        "runtime":{p.name:sha256(p) for p in runtime.glob("*.dll")}},indent=2),encoding="utf-8")
    print(executable,flush=True)


if __name__=="__main__": main()
