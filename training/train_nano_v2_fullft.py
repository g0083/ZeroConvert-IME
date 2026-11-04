# -*- coding: utf-8 -*-
"""
zenz-v3.1-xsmall Full Parameter Fine-Tuning (Full-FT) スクリプト
LoRAの制約を排し、25.6Mの全層・全重みをマイルドな学習率で最適化
Standard (zenz-v3.2-small) を確実に超える Nano v2 を生成する
"""

import os
import sys
import json
import argparse
from typing import Dict, List
from dataset_guard import checked_datasets, record_training_completed

import torch
from datasets import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    TrainingArguments,
    Trainer,
    DataCollatorForSeq2Seq
)

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_model", type=str, default="Miwa-Keita/zenz-v3.1-xsmall")
    parser.add_argument("--train_file", type=str, default="artifacts/datasets/separated-v1/distilled/train.jsonl")
    parser.add_argument("--val_file", type=str, default="artifacts/datasets/separated-v1/distilled/val.jsonl")
    parser.add_argument("--output_dir", type=str, default='artifacts/checkpoints/nano-v2-fullft')
    parser.add_argument("--batch_size", type=int, default=16)
    parser.add_argument("--grad_accum", type=int, default=2)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=2.5e-5)      # Full-FT用の極めて安全で滑らかな学習率
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
    train_raw, val_raw = checked_datasets(args.train_file, args.val_file, args.output_dir)
    print(f"Loading base model for Full-FT: {args.base_model}...", flush=True)

    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        device_map="auto" if torch.cuda.is_available() else None
    )

    # 全パラメータを訓練可能に設定 (Full Fine-Tuning)
    for param in model.parameters():
        param.requires_grad = True

    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Total trainable parameters (Full-FT): {trainable_params:,}", flush=True)

    print("Loading datasets...", flush=True)

    def preprocess_function(examples):
        input_ids_list = []
        labels_list = []
        for prompt, comp in zip(examples["prompt"], examples["completion"]):
            prompt_tokens = tokenizer.encode(prompt, add_special_tokens=False)
            comp_tokens = tokenizer.encode(comp, add_special_tokens=False) + [tokenizer.eos_token_id]

            full_tokens = (prompt_tokens + comp_tokens)[:args.max_length]
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
        warmup_steps=30,
        weight_decay=0.01,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=1,
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

    print("Starting Full Parameter Distillation Training...", flush=True)
    trainer.train()

    print(f"Saving Full-FT model to {args.output_dir}...", flush=True)
    trainer.save_model(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    record_training_completed(args.output_dir)
    print("Full-FT Training finished successfully!", flush=True)

if __name__ == "__main__":
    main()
