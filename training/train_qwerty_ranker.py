"""Standard v4 listwise reading ranking; original observation competes explicitly."""
import argparse
from contextlib import nullcontext
import json
from pathlib import Path
import random
import time

from peft import LoraConfig, TaskType, get_peft_model
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed

from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import sha256
from qwerty_corrector_policy import write_json
from qwerty_ranker import calibrate, encode_candidates, ranking_loss, sequence_scores
from train_nano_v3 import disable_all_dropout


def batch_tensors(encoded, device):
    width=max(len(r["input_ids"]) for r in encoded)
    return {"input_ids":torch.tensor([r["input_ids"]+[1]*(width-len(r["input_ids"])) for r in encoded],device=device),
        "attention_mask":torch.tensor([[1]*len(r["input_ids"])+[0]*(width-len(r["input_ids"])) for r in encoded],device=device),
        "labels":torch.tensor([r["labels"]+[-100]*(width-len(r["labels"])) for r in encoded],device=device)}


def autocast(device):
    return torch.autocast("cuda",dtype=torch.bfloat16) if str(device).startswith("cuda") else nullcontext()


def hf_records(model,tokenizer,rows):
    model.eval(); records=[]
    with torch.inference_mode():
        for row in rows:
            candidates=row["candidates"]
            if len(candidates)<=1:
                scores=[0.]*len(candidates)
            else:
                encoded=encode_candidates(tokenizer,row,candidates)
                batch=batch_tensors(encoded,model.device)
                with autocast(model.device):
                    output=model(input_ids=batch["input_ids"],attention_mask=batch["attention_mask"])
                    scores=sequence_scores(output.logits,batch["labels"]).float().cpu().tolist()
            records.append({"id":row["id"],"candidates":candidates,"scores":scores})
    return records


def training_items(rows):
    items=[]; skipped={"uncovered":0,"guard_only":0}
    for row in rows:
        positive=row["positive_index"]
        if positive is None: skipped["uncovered"]+=1; continue
        if len(row["candidates"])<4: skipped["guard_only"]+=1; continue
        negatives=[i for i in range(len(row["candidates"])) if i!=positive]
        # Include original (when incorrect), high-dictionary alternatives, and
        # a deterministic broader negative. No validation predictions are mined.
        choices=[positive]+negatives[:2]+[negatives[-1]]
        items.extend([(row,choices)]*(2 if row["expected_action"]=="keep" else 1))
    return items,skipped


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset",type=Path,required=True)
    p.add_argument("--run-name",required=True)
    p.add_argument("--base",type=Path,default=Path("artifacts/checkpoints/standard-v4-nano-transfer-merged"))
    p.add_argument("--epochs",type=int,default=3)
    p.add_argument("--lr",type=float,default=3e-5)
    a=p.parse_args()
    if Path(a.run_name).name!=a.run_name: raise ValueError("Expected one name")
    out=Path("artifacts/checkpoints")/(a.run_name+"-lora")
    dm=json.loads((a.dataset/"manifest.json").read_text(encoding="utf-8"))
    for s in ("train","val"):
        if sha256(a.dataset/f"{s}.jsonl")!=dm["outputs"][s]["sha256"]: raise ValueError("Data changed")
    train,val=checked_datasets(a.dataset/"train.jsonl",a.dataset/"val.jsonl",out)
    set_seed(42); torch.set_num_threads(4)
    tokenizer=AutoTokenizer.from_pretrained(a.base,local_files_only=True)
    if (tokenizer.eos_token_id,tokenizer.pad_token_id)!=(3,1): raise ValueError("Unexpected tokenizer")
    model=AutoModelForCausalLM.from_pretrained(a.base,local_files_only=True,dtype=torch.float32).to("cuda" if torch.cuda.is_available() else "cpu")
    model.config.use_cache=False
    model=get_peft_model(model,LoraConfig(task_type=TaskType.CAUSAL_LM,r=32,lora_alpha=64,use_dora=True,
        lora_dropout=0.,target_modules=["c_attn","c_proj","c_fc"],bias="none"))
    disable_all_dropout(model)
    items,skipped=training_items(train)
    if not items: raise ValueError("No covered training candidates")
    manifest={"arguments":{k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},
        "base":{p.name:sha256(p) for p in a.base.iterdir() if p.suffix in (".json",".safetensors")},
        "dataset_manifest_sha256":sha256(a.dataset/"manifest.json"),
        "sources":{p:sha256(p) for p in (__file__,"training/qwerty_ranker.py")},
        "recipe":{"rank":32,"alpha":64,"dora":True,"batch_observations":8,"candidates_per_train_observation":4,
            "keep_repeat":2,"score":"mean answer log likelihood including EOS","loss":"listwise CE(score*4) + .1 positive token CE", "seed":42},
        "training_items":len(items),"skipped":skipped,"status":"training"}
    write_json(out/"run-manifest.json",manifest)
    start=time.perf_counter()
    with model.disable_adapter(): baseline=hf_records(model,tokenizer,val)
    base_policy,grid=calibrate(val,baseline)
    write_json(out/"baseline-validation.json",{"selection":base_policy,"grid":grid,"records":baseline})
    optimizer=torch.optim.AdamW([p for p in model.parameters() if p.requires_grad],lr=a.lr,weight_decay=.01)
    best=-1.; history=[]; step=0
    total_steps=a.epochs*((len(items)+7)//8)
    for epoch in range(a.epochs):
        random.Random(42+epoch).shuffle(items); model.train()
        epoch_losses=[]
        for offset in range(0,len(items),8):
            encoded=[]
            for row,indices in items[offset:offset+8]:
                # Encode using all candidates first so train/eval share the same
                # context truncation even when only four candidates train.
                whole=encode_candidates(tokenizer,row,row["candidates"])
                encoded.extend(whole[i] for i in indices)
            batch=batch_tensors(encoded,model.device)
            optimizer.zero_grad(set_to_none=True)
            with autocast(model.device):
                output=model(input_ids=batch["input_ids"],attention_mask=batch["attention_mask"])
                loss=ranking_loss(sequence_scores(output.logits,batch["labels"]))
            if not torch.isfinite(loss): raise ValueError("Nonfinite loss")
            loss.backward(); torch.nn.utils.clip_grad_norm_(model.parameters(),1.)
            step+=1
            lr=a.lr*min(1.,step/30)*(.1+.9*(1-step/total_steps))
            for group in optimizer.param_groups: group["lr"]=lr
            optimizer.step(); epoch_losses.append(float(loss.detach()))
            if step%50==0: print("step",step,"loss",sum(epoch_losses[-50:])/len(epoch_losses[-50:]),flush=True)
        records=hf_records(model,tokenizer,val)
        selection,grid=calibrate(val,records)
        result={"epoch":epoch+1,"step":step,"loss":sum(epoch_losses)/len(epoch_losses),"selection":selection,"grid":grid}
        write_json(out/f"validation-epoch{epoch+1}.json",{**result,"records":records})
        history.append(result); print("validation",json.dumps(result,ensure_ascii=False),flush=True)
        if selection["metrics"]["f0_5"]>best:
            best=selection["metrics"]["f0_5"]; model.save_pretrained(out); tokenizer.save_pretrained(out)
            manifest["selected_epoch"]=epoch+1; manifest["selected_policy"]=selection
        write_json(out/"history.json",history)
    manifest.update(status="completed",seconds=time.perf_counter()-start,
        adapter_sha256=sha256(out/"adapter_model.safetensors"),peak_cuda_bytes=torch.cuda.max_memory_allocated() if torch.cuda.is_available() else None)
    write_json(out/"run-manifest.json",manifest); record_training_completed(out)
    print("Completed",out,"selected",manifest["selected_epoch"],flush=True)


if __name__=="__main__": main()
