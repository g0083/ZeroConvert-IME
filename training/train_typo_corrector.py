# -*- coding: utf-8 -*-
"""
zenz v3.1 / v3.2 ベース 誤入力（タイポ）補正特化モデル LoRA 学習スクリプト

前提環境:
pip install torch transformers peft datasets accelerate

実行方法:
python train_typo_corrector.py --base_model Miwa-Keita/zenz-v3.1-small --output_dir ./typo-corrector-lora
"""

import os
import sys
import json
import argparse
from typing import Dict, List
from dataset_guard import checked_datasets, record_training_completed
from corrector_encoding import encode_example

import torch
from datasets import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    TrainingArguments,
    Trainer,
    set_seed,
    DataCollatorForSeq2Seq
)
from peft import LoraConfig, get_peft_model, TaskType

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_model", type=str, default="Miwa-Keita/zenz-v3.1-small")
    parser.add_argument("--train_file", type=str, default="artifacts/datasets/separated-v1/typo/train.jsonl")
    parser.add_argument("--val_file", type=str, default="artifacts/datasets/separated-v1/typo/val.jsonl")
    parser.add_argument("--output_dir", type=str, default='artifacts/checkpoints/typo-corrector-lora')
    parser.add_argument("--batch_size", type=int, default=16)
    parser.add_argument("--grad_accum", type=int, default=2)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--lora_r", type=int, default=16)
    parser.add_argument("--lora_alpha", type=int, default=32)
    parser.add_argument("--lora_dropout", type=float, default=0.05)
    parser.add_argument("--max_length", type=int, default=128)
    parser.add_argument("--seed", type=int, default=42)
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
    # Seed before LoRA initialization as well as Trainer data shuffling.
    set_seed(args.seed)
    print(f"Loading base model: {args.base_model}...")

    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        dtype=torch.float32 if not torch.cuda.is_available() else torch.bfloat16,
        device_map="auto" if torch.cuda.is_available() else None
    )

    # LoRA 設定 (GPT-2 の Attention 射影層 c_attn, c_proj をターゲット)
    lora_config = LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        target_modules=["c_attn", "c_proj"],
        bias="none"
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    print("Loading datasets...")

    def preprocess_function(examples):
        input_ids_list = []
        labels_list = []
        for prompt, comp in zip(examples["prompt"], examples["completion"]):
            # prompt: \uEE02{ctx}\uEE00{reading}\uEE01
            # full: prompt + completion + <eos>
            encoded = encode_example(tokenizer, prompt, comp, args.max_length)
            input_ids_list.append(encoded["input_ids"])
            labels_list.append(encoded["labels"])

        return {"input_ids": input_ids_list, "labels": labels_list}

    train_ds = Dataset.from_list(train_raw).map(
        preprocess_function, batched=True, remove_columns=list(train_raw[0].keys())
    )
    val_ds = Dataset.from_list(val_raw).map(
        preprocess_function, batched=True, remove_columns=list(val_raw[0].keys())
    )

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        seed=args.seed,
        data_seed=args.seed,
        logging_steps=50,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        report_to="none",
        fp16=(torch.cuda.is_available() and not torch.cuda.is_bf16_supported()),
        bf16=(torch.cuda.is_available() and torch.cuda.is_bf16_supported())
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, pad_to_multiple_of=8)
    )

    print("Starting training...")
    trainer.train()

    print(f"Saving LoRA weights to {args.output_dir}...")
    model.save_pretrained(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    record_training_completed(args.output_dir)
    print("Training complete!")

if __name__ == "__main__":
    main()
