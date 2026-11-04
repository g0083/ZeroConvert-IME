"""Validate explicitly recorded human traces and freeze group-separated dev/test."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from dataset_isolation import evaluation_keys, load_jsonl, normalize, sha256, source_keys
from qwerty_corrector_policy import write_json
from qwerty_reading_search import ReadingSearch
from qwerty_trace import TraceReplay, generate_candidates


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--recordings",type=Path,required=True); p.add_argument("--training-dataset",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    reserved=evaluation_keys()
    for split in ("train","val"):
        reserved |= set().union(*(source_keys(r) for r in load_jsonl(a.training_dataset/f"{split}.jsonl")))
    replay=TraceReplay(); search=ReadingSearch(24); sets={"dev":[],"test":[]}; seen=set(); groups={}
    for record in load_jsonl(a.recordings):
        if not record.get("human_recorded"): raise ValueError("Synthetic traces cannot be imported as human")
        if record["id"] in seen: raise ValueError("Duplicate recorded trace")
        seen.add(record["id"])
        snapshots=replay.replay(record["events"]); obs=snapshots[-1]
        if not obs["observed_keys"]: raise ValueError("Empty composition is not a correction evaluation case")
        for key in obs:
            if obs[key]!=record[key]: raise ValueError("Recorded native state does not reproduce: "+key)
        action=record["expected_action"]
        if action not in ("repair","keep","wait"): raise ValueError("Invalid action")
        intended=normalize(record["intended_reading"])
        expected=normalize(record["target_reading"])
        if action!="repair" and expected!=normalize(obs["native_observed"]): raise ValueError("Keep/wait label changes reading")
        if action=="repair" and (expected!=intended or expected==normalize(obs["native_observed"])): raise ValueError("Ambiguous repair label")
        group=hashlib.sha256(intended.encode()).hexdigest()
        row={**record,"source_group":group,"input_reading":obs["native_observed"],
            "expected":record["reference_surface"] if action=="repair" else obs["native_observed"],"snapshots":snapshots}
        if source_keys(row)&reserved: raise ValueError("Human evaluation overlaps existing training/evaluation source")
        split="dev" if int(hashlib.sha256(("human-v1:"+group).encode()).hexdigest(),16)%3==0 else "test"
        groups.setdefault(group,[]).append(row)
        row["candidates"]=generate_candidates(search,obs); sets[split].append(row)
    # Link equivalent input/target/surface identities across intended-source
    # groups; reject a split conflict rather than silently changing a partition.
    owners={}
    for split,rows in sets.items():
        for row in rows:
            for key in source_keys(row):
                if key in owners and owners[key]!=split: raise ValueError("Linked human source crosses split")
                owners[key]=split
    if not all(sets.values()): raise ValueError("Need independent source groups in both dev and test")
    a.out.mkdir(parents=True); outputs={}
    for split,rows in sets.items():
        path=a.out/f"{split}.jsonl"
        path.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows),encoding="utf-8")
        outputs[split]={"sha256":sha256(path),"rows":len(rows),"groups":len({r['source_group'] for r in rows}),
            "actions":dict(Counter(r['expected_action'] for r in rows))}
    write_json(a.out/"manifest.json",{"outputs":outputs,"recordings_sha256":sha256(a.recordings),"source_sha256":sha256(__file__),
        "native":replay.native.identity,"scope":"Human recorded test-window events; independent adjudication is recorded per case; not normal user-frequency measurement"})
    print(json.dumps(outputs,ensure_ascii=False),flush=True)


if __name__=="__main__": main()
