# -*- coding: utf-8 -*-
"""
zerocon model nano v2 蒸留ファインチューニングスクリプト
Base: Miwa-Keita/zenz-v3.1-xsmall
Teacher: zenz-v3.2-small の蒸留データ & 辞書データ
"""

import os
import sys
import json
import argparse
from typing import Dict, List

import torch
from datasets import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    TrainingArguments,
    Trainer,
    DataCollatorForSeq2Seq
)
from peft import LoraConfig, get_peft_model, TaskType

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_model", type=str, default="Miwa-Keita/zenz-v3.1-xsmall")
    parser.add_argument("--train_file", type=str, default="data/distilled_train.jsonl")
    parser.add_argument("--val_file", type=str, default="data/distilled_val.jsonl")
    parser.add_argument("--output_dir", type=str, default='artifacts/checkpoints/nano-v2-lora')
    parser.add_argument("--batch_size", type=int, default=16)
    parser.add_argument("--grad_accum", type=int, default=2)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--lora_r", type=int, default=32)
    parser.add_argument("--lora_alpha", type=int, default=64)
    parser.add_argument("--lora_dropout", type=float, default=0.05)
    parser.add_argument("--max_length", type=int, default=128)
    return parser.parse_args()

def load_jsonl(path: str) -> List[Dict]:
    data = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                data.append(json.loads(line))
    return data

def main():
    args = parse_args()
    print(f"Loading base model: {args.base_model}...", flush=True)

    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.float32 if not torch.cuda.is_available() else torch.bfloat16,
        device_map="auto" if torch.cuda.is_available() else None
    )

    # LoRA 設定: Attention および MLP の全射影層を対象
    lora_config = LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        target_modules=["c_attn", "c_proj", "c_fc"],
        bias="none"
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    print("Loading datasets...", flush=True)
    train_raw = load_jsonl(args.train_file)
    val_raw = load_jsonl(args.val_file)

    def preprocess_function(examples):
        input_ids_list = []
        labels_list = []
        for prompt, comp in zip(examples["prompt"], examples["completion"]):
            prompt_tokens = tokenizer.encode(prompt, add_special_tokens=False)
            comp_tokens = tokenizer.encode(comp, add_special_tokens=False) + [tokenizer.eos_token_id]

            full_tokens = (prompt_tokens + comp_tokens)[:args.max_length]
            # prompt 部分の損失はマスク (-100)
            labels = ([-100] * len(prompt_tokens) + comp_tokens)[:args.max_length]

            input_ids_list.append(full_tokens)
            labels_list.append(labels)

        return {"input_ids": input_ids_list, "labels": labels_list}

    train_ds = Dataset.from_list(train_raw).map(preprocess_function, batched=True)
    val_ds = Dataset.from_list(val_raw).map(preprocess_function, batched=True)

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=50,
        weight_decay=0.01,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        logging_steps=20,
        bf16=torch.cuda.is_available(),
        fp16=False,
        report_to="none"
    )

    collator = DataCollatorForSeq2Seq(
        tokenizer,
        pad_to_multiple_of=8,
        return_tensors="pt",
        padding=True
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=collator
    )

    print("Starting training...", flush=True)
    trainer.train()

    print(f"Saving best LoRA adapter to {args.output_dir}...", flush=True)
    trainer.save_model(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    print("Training finished successfully!", flush=True)

if __name__ == "__main__":
    main()
