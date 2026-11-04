"""Audit frozen candidate sets, source separation, physical witnesses and labels."""
import argparse
import json
from pathlib import Path

from dataset_isolation import evaluation_keys, load_jsonl, sha256, source_keys
from qwerty_corrector_policy import write_json
from qwerty_local_contract import one_edit
from native_qwerty import NativeQwerty


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset",type=Path,required=True); p.add_argument("--prospective",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    sets={s:load_jsonl(a.dataset/f"{s}.jsonl") for s in ("train","val")}
    sets.update({s:load_jsonl(a.prospective/f"{s}.jsonl") for s in ("dev","test")})
    native=NativeQwerty(); results={}; identities={}
    for split,rows in sets.items():
        keys=set(); groups=set(); ids=set(); witnesses=0; active_keep=0
        for row in rows:
            if row["id"] in ids: raise ValueError("Duplicate id: "+row["id"])
            ids.add(row["id"]); keys|=source_keys(row); groups.add(row["source_group"])
            candidates=row["candidates"]
            if not candidates or not candidates[0]["unchanged"]: raise ValueError("Missing original")
            if len(candidates)>24 or len({c['reading'] for c in candidates})!=len(candidates): raise ValueError("Invalid bounded candidate set")
            if row["expected_action"]=="repair" and row["target_reading"]==row["native_observed"]: raise ValueError("Repair with unchanged reading")
            if row["expected_action"]!="repair" and row["target_reading"]!=row["native_observed"]: raise ValueError("Keep label changes reading")
            for c in candidates:
                if native.decode(c["keys"])!=c["reading"]: raise ValueError("Native witness mismatch")
                if not c["unchanged"]:
                    if not one_edit(row["observed_keys"],c["keys"]): raise ValueError("Not one physical edit")
                    witnesses+=1
            if row["expected_action"]=="keep" and len(candidates)>1: active_keep+=1
            if row.get("positive_index") is not None:
                if candidates[row['positive_index']]["reading"]!=row["target_reading"]: raise ValueError("Wrong positive")
        identities[split]=(keys,groups)
        results[split]={"rows":len(rows),"groups":len(groups),"verified_changed_witnesses":witnesses,"active_keep":active_keep}
    for i,left in enumerate(sets):
        for right in list(sets)[i+1:]:
            if identities[left][0]&identities[right][0] or identities[left][1]&identities[right][1]:
                raise ValueError("Cross-split source collision: "+left+"/"+right)
    if any(identities[s][0]&evaluation_keys() for s in sets): raise ValueError("Legacy evaluation source collision")
    write_json(a.out,{"status":"PASS","results":results,"files":{str(p):sha256(p) for p in [a.dataset/"manifest.json",a.prospective/"manifest.json"]},"native":native.identity})
    print(json.dumps(results,ensure_ascii=False),flush=True)


if __name__=="__main__": main()
