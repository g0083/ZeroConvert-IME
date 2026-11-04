"""Shared long-input augmentation for full-surface vs local-patch training."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random

from build_origin_corpus import romanize
from build_qwerty_corrector import mutations, pending
from dataset_isolation import load_jsonl, sha256, source_keys, validate_rows, evaluation_keys
from native_qwerty import NativeQwerty
from qwerty_local_contract import make_target, parse_prediction

TEMPLATES=[("ここでは","ここでは","という表現を使います","というひょうげんをつかいます"),
 ("","","という言葉を覚えました","ということばをおぼえました"),
 ("","","という表現について考えています","というひょうげんについてかんがえています"),
 ("","","という言葉の意味を調べます","ということばのいみをしらべます"),
 ("今回は","こんかいは","という表現を確認します","というひょうげんをかくにんします"),
 ("","","という言葉を初めて聞きました","ということばをはじめてききました"),
 ("","","という表現をノートに書きます","というひょうげんをのーとにかきます"),
 ("本日は","ほんじつは","という言葉について説明します","ということばについてせつめいします")]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--match-ids",type=Path)
    a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    parent=Path("artifacts/datasets/corrector-qwerty-state-20261030-r1")
    parts={s:load_jsonl(parent/f"{s}.jsonl") for s in ("train","val","test")}
    native=NativeQwerty(); counts=Counter()
    for split in ("train","val"):
        seeds={}
        for r in parts[split]:
            if r.get("canonical_keys") and r["expected_action"]=="keep" and r["typo_type"] in ("clean","valid_spelling"):
                seeds.setdefault(r["source_group"],r)
        for group,r in sorted(seeds.items()):
            rng=random.Random(group)
            pool=TEMPLATES[:6] if split=="train" else TEMPLATES[6:]
            ps,pr,ss,sr=pool[rng.randrange(len(pool))]
            reading=pr+r["canonical_reading"]+sr
            surface=ps+r["canonical_surface"]+ss
            keys=romanize(reading)
            if not keys.isascii() or len(keys)>96 or native.decode(keys)!=reading:
                counts["long_roundtrip_excluded"]+=1; continue
            variants=[(keys,"clean","append"),(keys+"k","pending","append")]
            cut=max(len(keys)//2,len(romanize(pr+r["canonical_reading"]))+2)
            while cut<len(keys)-1 and pending(native.decode(keys[:cut])): cut+=1
            if cut<len(keys)-1: variants.append((keys[:cut],"editing_prefix","backspace"))
            kinds=set()
            for wrong,kind,_ in mutations(keys,rng):
                if kind not in kinds:
                    variants.append((wrong,kind,"append")); kinds.add(kind)
            for i,(raw,kind,state) in enumerate(variants):
                observed=native.decode(raw)
                hold=bool(pending(observed))
                keep=kind in ("clean","editing_prefix") or observed==reading
                action="wait" if hold else "keep" if keep else "repair"
                target=observed if hold or kind=="editing_prefix" else surface
                row={"id":f"long-{group[:16]}-{i}","source_group":group,"context":"","left_context":"",
                    "observed_keys":raw,"canonical_keys":keys,"native_observed":observed,"input_reading":observed,
                    "input_state":state,"cursor_key_offset":len(raw),"expected_action":action,"expected":target,
                    "completion":target,"target_reading":observed if hold or kind=="editing_prefix" else reading,
                    "canonical_reading":reading,"canonical_surface":surface,"is_clean":action!="repair","typo_type":kind,
                    "prompt":"\uee00"+observed+"\uee01","acceptable":[],"long_augmented":True,
                    "provenance":"Metalinguistic synthetic template over matching source group; not a natural user sentence"}
                parts[split].append(row)
    reserved=evaluation_keys()
    fresh=load_jsonl("artifacts/datasets/corrector-qwerty-fresh-20261030-r1/test.jsonl")
    reserved|=set().union(*(source_keys(r) for r in fresh))
    for split,rows in parts.items():
        allowed={r["id"] for r in load_jsonl(a.match_ids/f"{split}.jsonl")} if a.match_ids else None
        kept=[]
        for row in rows:
            if allowed is not None and row["id"] not in allowed: continue
            if split!="test":
                try:
                    label=make_target(row)
                    if row["expected_action"]=="repair":
                        parsed=parse_prediction(label,row,native)
                        if not parsed["valid"] or parsed["reading"]!=row["target_reading"]: raise ValueError("Reading mismatch")
                    row["local_completion"]=label
                except ValueError:
                    counts[split+"_unrepresentable"]+=1; continue
            kept.append(row)
        parts[split]=kept
    # Drop train source groups colliding with heldouts; never move heldout rows.
    held=set().union(*(source_keys(r) for s in ("val","test") for r in parts[s]))|reserved
    blocked={r["source_group"] for r in parts["train"] if source_keys(r)&held}
    parts["train"]=[r for r in parts["train"] if r["source_group"] not in blocked]
    validate_rows(parts["train"],parts["val"]+parts["test"],reserved)
    validate_rows(parts["val"],parts["test"],reserved)
    a.out.mkdir(parents=True)
    manifest={"parent_manifest_sha256":sha256(parent/"manifest.json"),"native":native.identity,"counts":dict(counts),
        "blocked_train_groups":len(blocked),"sources":{str(Path(__file__)):sha256(__file__),"training/qwerty_local_contract.py":sha256("training/qwerty_local_contract.py")},"outputs":{}}
    if a.match_ids: manifest["matched_row_ids_from"]={str(a.match_ids/f"{s}.jsonl"):sha256(a.match_ids/f"{s}.jsonl") for s in parts}
    for split,rows in parts.items():
        path=a.out/f"{split}.jsonl"
        path.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows),encoding="utf-8")
        manifest["outputs"][split]={"rows":len(rows),"sha256":sha256(path),"actions":dict(Counter(r["expected_action"] for r in rows)),
            "long_rows":sum(bool(r.get("long_augmented")) for r in rows),"groups":len({r["source_group"] for r in rows})}
    (a.out/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest["outputs"],ensure_ascii=False,indent=2))


if __name__=="__main__": main()
