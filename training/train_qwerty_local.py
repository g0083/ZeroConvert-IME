"""Matched Nano v4 full-surface/local-patch training; no inference search model."""
import argparse
import json
from pathlib import Path

import torch
from datasets import Dataset
from peft import LoraConfig, TaskType, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments, set_seed

from corrector_encoding import encode_example
from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import evaluation_keys, load_jsonl, sha256, source_keys, validate_rows
from evaluate_v4_repair_comparison import ReadingVerifier
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import cohort, DICTIONARY, write_json
from qwerty_local_eval import input_prompt, interpret, metrics
from train_nano_v3 import disable_all_dropout
from train_qwerty_corrector import QwertyTrainer
from qwerty_repair_loss import encode_repair,repair_loss


def generate_hf(model,tokenizer,rows,mode):
    old=tokenizer.padding_side; tokenizer.padding_side="left"
    was_training=model.training; model.eval()
    values=[]
    try:
        with torch.inference_mode():
            for start in range(0,len(rows),32):
                batch=tokenizer([input_prompt(r) for r in rows[start:start+32]],add_special_tokens=False,padding=True,return_tensors="pt").to(model.device)
                output=model.generate(**batch,do_sample=False,max_new_tokens=48 if mode=="local" else 128,eos_token_id=3,pad_token_id=1,use_cache=True)
                for seq in output[:,batch["input_ids"].shape[1]:].tolist():
                    ended=3 in seq
                    answer=seq[:seq.index(3)] if ended else seq
                    values.append({"text":tokenizer.decode(answer,skip_special_tokens=False) if ended else "", "ended":ended,"tokens":len(answer)})
    finally:
        tokenizer.padding_side=old; model.train(was_training)
    return values


class LocalTrainer(QwertyTrainer):
    def __init__(self,*args,mode,validation_rows,repair_weight=1.,copy_margin_weight=0.,copy_margin=2.,**kwargs):
        super().__init__(*args,fixture={},tokenizer_for_generation=None,baseline={},**kwargs)
        self.mode=mode; self.validation_rows=validation_rows
        self.native=NativeQwerty(); self.verifier=ReadingVerifier(DICTIONARY)
        self.repair_weight=repair_weight; self.copy_margin_weight=copy_margin_weight; self.copy_margin=copy_margin

    def compute_loss(self,model,inputs,return_outputs=False,num_items_in_batch=None):
        if "token_weights" not in inputs:
            return super().compute_loss(model,inputs,return_outputs,num_items_in_batch)
        output=model(**{k:inputs[k] for k in ("input_ids","attention_mask") if k in inputs})
        loss=repair_loss(output.logits,inputs["labels"],inputs["token_weights"],inputs["anti_labels"],self.copy_margin_weight,self.copy_margin)
        return (loss,output) if return_outputs else loss

    def evaluate(self,*args,**kwargs):
        result=Trainer.evaluate(self,*args,**kwargs)
        with self.accelerator.autocast():
            outputs=generate_hf(self.model,self.processing_class,self.validation_rows,self.mode)
        predictions=[interpret(o["text"],r,self.mode,self.native,self.verifier) for r,o in zip(self.validation_rows,outputs)]
        values=metrics(self.validation_rows,predictions,self.verifier)
        excess=max(0,values["keep_proposed"]-1)+values["wait_proposed"]+max(0,values["preservation_proposed"]-1)
        score=values["reading_f0_5"]-10.*excess
        result.update(eval_selection=score,eval_reading_correct=values["reading_correct"],eval_keep_proposed=values["keep_proposed"],eval_wait_proposed=values["wait_proposed"])
        write_json(Path(self.args.output_dir)/f"validation-step{self.state.global_step}.json",{"metrics":values,"eligible":excess==0,
            "cases":[{"id":r["id"],"response":o,"prediction":p} for r,o,p in zip(self.validation_rows,outputs,predictions)]})
        self.log({k:v for k,v in result.items() if k!="eval_loss"})
        return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name",required=True)
    p.add_argument("--mode",choices=("full","local"),required=True)
    p.add_argument("--dataset",type=Path,required=True)
    p.add_argument("--epochs",type=int,default=3)
    p.add_argument("--lr",type=float,default=1.2e-4)
    p.add_argument("--repair-weight",type=float,default=1.)
    p.add_argument("--copy-margin-weight",type=float,default=0.)
    p.add_argument("--copy-margin",type=float,default=2.)
    a=p.parse_args()
    if Path(a.run_name).name!=a.run_name: raise ValueError("Expected one run-name component")
    enhanced=a.repair_weight!=1. or a.copy_margin_weight!=0.
    if enhanced and a.mode!="local": raise ValueError("Repair objectives require local mode")
    if a.repair_weight<=0 or a.copy_margin_weight<0 or a.copy_margin<0: raise ValueError("Invalid loss weights")
    out=Path("artifacts/checkpoints")/(a.run_name+"-lora")
    base=Path("artifacts/checkpoints/nano-v4-argon4-merged")
    dm=json.loads((a.dataset/"manifest.json").read_text(encoding="utf-8"))
    for split in ("train","val","test"):
        if sha256(a.dataset/f"{split}.jsonl")!=dm["outputs"][split]["sha256"]: raise ValueError("Dataset changed")
    train,val=checked_datasets(a.dataset/"train.jsonl",a.dataset/"val.jsonl",out)
    fresh=load_jsonl("artifacts/datasets/corrector-qwerty-fresh-20261030-r1/test.jsonl")
    reserved=evaluation_keys()|set().union(*(source_keys(r) for r in fresh))
    validate_rows(train,val+load_jsonl(a.dataset/"test.jsonl"),reserved)
    old=json.loads(Path("artifacts/results/corrector-qwerty-state-20261030-r1-val-fixture.json").read_text(encoding="utf-8"))["cases"]
    lookup={r["id"]:r for r in val}
    selection=[lookup[r["id"]] for r in old if r["id"] in lookup]
    selection+=cohort([r for r in val if r.get("long_augmented")],{"repair":96,"keep":32,"wait":32})
    set_seed(42); torch.set_num_threads(4)
    tokenizer=AutoTokenizer.from_pretrained(base,local_files_only=True)
    if (tokenizer.bos_token_id,tokenizer.eos_token_id,tokenizer.pad_token_id)!=(2,3,1): raise ValueError("Unexpected tokenizer IDs")
    manifest={"arguments":{**vars(a),"dataset":str(a.dataset),"base_model":str(base)},"base":{p.name:sha256(p) for p in base.iterdir() if p.is_file() and p.suffix in (".json",".safetensors")},
        "dataset_manifest_sha256":sha256(a.dataset/"manifest.json"),"sources":{p:sha256(p) for p in [__file__,"training/qwerty_repair_loss.py","training/qwerty_local_contract.py","benchmarks/research/qwerty_local_eval.py","training/qwerty_input_contract.py"]},
        "recipe":{"rank":32,"alpha":64,"dora":True,"targets":["c_attn","c_proj","c_fc"],"keep_repetitions":3,"max_length":384,"seed":42,"selection":"reading F0.5; keep<=1, wait=0, canary<=1"}}
    write_json(out/"run-manifest.json",manifest); write_json(out/"validation-fixture.json",{"cases":selection,"scope":"Validation selection only; existing core plus disjoint-source long template validation"})
    device="cuda" if torch.cuda.is_available() else "cpu"
    model=AutoModelForCausalLM.from_pretrained(base,dtype=torch.float32,local_files_only=True).to(device)
    model=get_peft_model(model,LoraConfig(task_type=TaskType.CAUSAL_LM,r=32,lora_alpha=64,use_dora=True,lora_dropout=0.,target_modules=["c_attn","c_proj","c_fc"],bias="none"))
    disable_all_dropout(model)
    def encode(rows,balance=False):
        items=[]
        for row in rows:
            target=row["local_completion"] if a.mode=="local" else row["completion"]
            value=encode_repair(tokenizer,input_prompt(row),target,384,a.repair_weight) if enhanced else encode_example(tokenizer,input_prompt(row),target,384)
            items.extend([value]*(3 if balance and row["expected_action"]=="keep" else 1))
        return Dataset.from_list(items)
    base_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer,pad_to_multiple_of=8)
    def collate(rows):
        batch=base_collator([{k:v for k,v in r.items() if k not in ("token_weights","anti_labels")} for r in rows])
        if enhanced:
            width=batch["input_ids"].shape[1]
            batch["token_weights"]=torch.tensor([r["token_weights"]+[0.]*(width-len(r["token_weights"])) for r in rows])
            batch["anti_labels"]=torch.tensor([r["anti_labels"]+[-100]*(width-len(r["anti_labels"])) for r in rows])
        return batch
    trainer=LocalTrainer(model=model,mode=a.mode,validation_rows=selection,processing_class=tokenizer,
        repair_weight=a.repair_weight,copy_margin_weight=a.copy_margin_weight,copy_margin=a.copy_margin,
        args=TrainingArguments(output_dir=str(out),num_train_epochs=a.epochs,learning_rate=a.lr,per_device_train_batch_size=32,per_device_eval_batch_size=32,
            weight_decay=.01,warmup_steps=30,lr_scheduler_type="cosine",eval_strategy="epoch",save_strategy="epoch",save_total_limit=2,
            load_best_model_at_end=True,metric_for_best_model="eval_selection",greater_is_better=True,bf16=device=="cuda",seed=42,data_seed=42,
            logging_steps=100,report_to="none",disable_tqdm=True,remove_unused_columns=not enhanced),train_dataset=encode(train,True),eval_dataset=encode(val),
        data_collator=collate)
    trainer.train(); trainer.save_model(str(out)); tokenizer.save_pretrained(out); trainer.state.save_to_json(str(out/"trainer_state.json")); record_training_completed(out)
    print("Selected",trainer.state.best_model_checkpoint,trainer.state.best_metric,flush=True)


if __name__=="__main__": main()
