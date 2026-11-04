"""Freeze train/validation candidate sets; never insert a gold-only candidate."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from dataset_isolation import evaluation_keys, load_jsonl, sha256, validate_rows
from qwerty_corrector_policy import write_json
from qwerty_reading_search import ReadingSearch


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--parent",type=Path,default=Path("artifacts/datasets/corrector-qwerty-local-20261030-r4"))
    p.add_argument("--train-limit",type=int,default=3072)
    p.add_argument("--val-limit",type=int,default=384)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    train=load_jsonl(a.parent/"train.jsonl"); val=load_jsonl(a.parent/"val.jsonl")
    reserved=evaluation_keys()
    fresh=load_jsonl("artifacts/datasets/corrector-qwerty-fresh-20261030-r1/test.jsonl")
    from dataset_isolation import source_keys
    reserved |= set().union(*(source_keys(r) for r in fresh))
    validate_rows(train,val+load_jsonl(a.parent/"test.jsonl"),reserved)
    generator=ReadingSearch(24)
    a.out.mkdir(parents=True)
    manifest={"scope":"Pilot; existing synthetic training groups. Not recorded human typing.",
        "arguments":{k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},
        "parents":{s:sha256(a.parent/f"{s}.jsonl") for s in ("train","val","test")},
        "native":generator.native.identity,"source_sha256":sha256(__file__),
        "generator_sha256":sha256("benchmarks/research/qwerty_reading_search.py"),"outputs":{}}
    for split,source,limit in (("train",train,a.train_limit),("val",val,a.val_limit)):
        # Round robin across source groups/actions/length buckets, deterministic.
        buckets=defaultdict(list)
        for r in source:
            if r.get("observed_keys"):
                buckets[(r["source_group"],r["expected_action"],len(r["observed_keys"])>=40)].append(r)
        for bucket in buckets.values(): bucket.sort(key=lambda r:hashlib.sha256(r["id"].encode()).hexdigest())
        selected=[]
        while buckets and len(selected)<limit:
            for key in sorted(list(buckets)):
                selected.append(buckets[key].pop())
                if not buckets[key]: del buckets[key]
                if len(selected)==limit: break
        rows=[]; stats=Counter()
        path=a.out/f"{split}.jsonl"
        with path.open("x",encoding="utf-8") as stream:
            for i,row in enumerate(selected):
                obs={k:row.get(k) for k in ("observed_keys","input_state","cursor_key_offset")}
                candidates=generator.generate(obs)
                if not candidates: stats["no_candidates"]+=1; continue
                target=row["target_reading"] if row["expected_action"]=="repair" else candidates[0]["reading"]
                positive=next((j for j,c in enumerate(candidates) if c["reading"]==target),None)
                stats["rows"]+=1; stats[row["expected_action"]]+=1
                stats["repair_covered"]+=row["expected_action"]=="repair" and positive is not None
                record={**row,"candidates":candidates,"positive_index":positive}
                rows.append(record); stream.write(json.dumps(record,ensure_ascii=False)+"\n")
                if i%100==0: stream.flush(); print(split,i,len(selected),flush=True)
        manifest["outputs"][split]={"sha256":sha256(path),"stats":dict(stats),"groups":len({r['source_group'] for r in rows})}
        write_json(a.out/"manifest.json",manifest)
    print(json.dumps(manifest["outputs"],ensure_ascii=False),flush=True)


if __name__=="__main__": main()
