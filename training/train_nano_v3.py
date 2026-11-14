# -*- coding: utf-8 -*-
"""ZeroCon Model Nano v4 (Standard-Parity Edition):
Super-Teacher Dual-Anchor Target-Corrected KL + Self-Copy Contrastive Margin + 6-Layer DoRA.

Student Base: artifacts/models/hf/zenz-v3.2-xsmall (Apache-2.0, 6 layers, d=512, 26.7M)
Super-Teacher: artifacts/checkpoints/standard-v3-argon4-r2-merged (Apache-2.0, 12 layers, d=768, 90.5M)
Dataset: artifacts/datasets/standard-v3-knowledge/
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))

from dataset_guard import checked_datasets, record_training_completed  # noqa: E402

import torch
import torch.nn as nn
import torch.nn.functional as F
from datasets import Dataset
from peft import LoraConfig, PeftModel, TaskType, get_peft_model
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForSeq2Seq,
    Trainer,
    TrainingArguments,
)

SPACE_UNK_TOKEN_ID = 0      # llama.cpp maps ASCII ' ' (0x20 -> 'Ġ') to [UNK] (0)
SPACE_U3000_TOKEN_ID = 567  # Full-width space '\u3000' (only space token in zenz-v3.2 vocab)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--student_model", type=str, default="artifacts/models/hf/zenz-v3.2-xsmall")
    parser.add_argument("--teacher_model", type=str, default="artifacts/checkpoints/standard-v3-argon4-r2-merged")
    parser.add_argument("--train_file", type=str, default="artifacts/datasets/standard-v3-knowledge/train.jsonl")
    parser.add_argument("--val_file", type=str, default="artifacts/datasets/standard-v3-knowledge/val.jsonl")
    parser.add_argument("--output_dir", type=str, default="artifacts/checkpoints/nano-v4-argon4-lora")
    parser.add_argument("--merged_dir", type=str, default="artifacts/checkpoints/nano-v4-argon4-merged")
    parser.add_argument("--run_name", type=str, default="zerocon-nano-v4")
    parser.add_argument("--batch_size", type=int, default=8)
    parser.add_argument("--grad_accum", type=int, default=1)
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--lr", type=float, default=1.6e-4)
    parser.add_argument("--lora_r", type=int, default=64)
    parser.add_argument("--lora_alpha", type=int, default=128)
    parser.add_argument("--lora_dropout", type=float, default=0.0)
    parser.add_argument("--temperature", type=float, default=2.0)
    parser.add_argument("--margin_gamma", type=float, default=2.2)
    parser.add_argument("--self_copy_gamma", type=float, default=3.0)
    parser.add_argument("--max_length", type=int, default=128)
    parser.add_argument("--use_dora", action="store_true", default=True)
    return parser.parse_args()


def disable_all_dropout(model: nn.Module):
    for module in model.modules():
        if isinstance(module, nn.Dropout):
            module.p = 0.0


def encode_with_space_token(tokenizer, text: str, space_token_id: int) -> list[int]:
    """Encode text while mapping ASCII space ' ' explicitly to space_token_id."""
    if " " not in text:
        return tokenizer.encode(text, add_special_tokens=False)
    parts = text.split(" ")
    tokens: list[int] = []
    for idx, part in enumerate(parts):
        if idx > 0:
            tokens.append(space_token_id)
        if part:
            tokens.extend(tokenizer.encode(part, add_special_tokens=False))
    return tokens


class SuperTeacherDualAnchorTrainer(Trainer):
    def __init__(self, teacher_model, temperature=2.0, margin_gamma=2.2, self_copy_gamma=3.0, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.teacher_model = teacher_model
        self.temperature = temperature
        self.margin_gamma = margin_gamma
        self.self_copy_gamma = self_copy_gamma

    def create_optimizer(self):
        """Measure 2: 6-Layer (h.0..5) Layer-wise Learning Rates + DoRA magnitude weight-decay exclusion."""
        if self.optimizer is not None:
            return self.optimizer

        base_lr = self.args.learning_rate
        weight_decay = self.args.weight_decay

        groups = {
            "low_decay": {"params": [], "lr": base_lr * 0.45, "weight_decay": weight_decay},
            "low_nodecay": {"params": [], "lr": base_lr * 0.45, "weight_decay": 0.0},
            "mid_decay": {"params": [], "lr": base_lr * 1.00, "weight_decay": weight_decay},
            "mid_nodecay": {"params": [], "lr": base_lr * 1.00, "weight_decay": 0.0},
            "high_decay": {"params": [], "lr": base_lr * 1.35, "weight_decay": weight_decay},
            "high_nodecay": {"params": [], "lr": base_lr * 1.35, "weight_decay": 0.0},
        }

        for name, param in self.model.named_parameters():
            if not param.requires_grad:
                continue
            is_nodecay = ("bias" in name) or ("lora_magnitude_vector" in name) or (param.ndim < 2)
            tier = "mid"
            for layer_idx in range(6):
                if f".h.{layer_idx}." in name:
                    if layer_idx <= 1:
                        tier = "low"
                    elif layer_idx <= 3:
                        tier = "mid"
                    else:
                        tier = "high"
                    break
            key = f"{tier}_{'nodecay' if is_nodecay else 'decay'}"
            groups[key]["params"].append(param)

        optimizer_grouped_parameters = [g for g in groups.values() if len(g["params"]) > 0]
        optimizer_cls, optimizer_kwargs = Trainer.get_optimizer_cls_and_kwargs(self.args, self.model)
        self.optimizer = optimizer_cls(optimizer_grouped_parameters, **optimizer_kwargs)
        return self.optimizer

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        input_ids = inputs["input_ids"]
        attention_mask = inputs.get("attention_mask")
        labels = inputs["labels"]

        # 1. Trainable Student forward pass (0.0 dropout guaranteed)
        outputs = model(input_ids=input_ids, attention_mask=attention_mask)
        student_logits = outputs.logits

        # 2. Frozen Super-Teacher (Standard v3) & Frozen Base Nano forward passes
        with torch.no_grad():
            teacher_logits = self.teacher_model(
                input_ids=input_ids, attention_mask=attention_mask
            ).logits

            with model.disable_adapter():
                base_logits = model(
                    input_ids=input_ids, attention_mask=attention_mask
                ).logits

        # Shift for next-token causal prediction
        shift_s = student_logits[:, :-1, :].contiguous()
        shift_t = teacher_logits[:, :-1, :].contiguous()
        shift_b = base_logits[:, :-1, :].contiguous()
        shift_labels = labels[:, 1:].contiguous()

        valid_mask = shift_labels != -100
        if not valid_mask.any():
            loss = shift_s.sum() * 0.0
            return (loss, outputs) if return_outputs else loss

        # Detect intra-sequence self-copying (Induction Head copying 1st clause kanji into 2nd clause)
        with torch.no_grad():
            shift_b_pred = shift_b.argmax(dim=-1)  # [B, L]
            seq_len = shift_labels.size(1)
            tril_mask = torch.tril(
                torch.ones((seq_len, seq_len), device=shift_labels.device, dtype=torch.bool),
                diagonal=-1,
            ).unsqueeze(0)  # [1, L, L]
            valid_prior = (shift_labels != -100).unsqueeze(1)  # [B, 1, L]
            matches_prior = (shift_b_pred.unsqueeze(2) == shift_labels.unsqueeze(1)) & tril_mask & valid_prior
            is_self_copy_2d = matches_prior.any(dim=-1)  # [B, L]
            is_self_copy = is_self_copy_2d[valid_mask]

        s_flat = shift_s[valid_mask].float()
        t_flat = shift_t[valid_mask].float()
        b_flat = shift_b[valid_mask].float()
        y_flat = shift_labels[valid_mask]

        temp = self.temperature
        ce_loss = F.cross_entropy(s_flat, y_flat, reduction="none")
        log_p_s = F.log_softmax(s_flat / temp, dim=-1)

        with torch.no_grad():
            is_eos = y_flat == 3

            # At EOS (y_flat == 3), suppress spurious trailing hallucinations (\uee08=172, 。=248, !=4, ！=469)
            b_clean = b_flat.clone()
            t_clean = t_flat.clone()
            if is_eos.any():
                for h_id in (172, 248, 4, 469):
                    b_clean[is_eos, h_id] = -10.0
                    t_clean[is_eos, h_id] = -10.0

            b_pred = b_clean.argmax(dim=-1)
            t_pred = t_clean.argmax(dim=-1)
            base_agrees = b_pred == y_flat

            p_b = F.softmax(b_clean / temp, dim=-1)

            # Target-Corrected Base Nano distribution (swap b_pred and y_flat when Base is wrong)
            b_corr = b_clean.clone()
            row_idx = torch.arange(b_flat.size(0), device=b_flat.device)
            b_y_val = b_clean[row_idx, y_flat]
            b_pred_val = b_clean[row_idx, b_pred]
            b_corr[row_idx, b_pred] = b_y_val
            b_corr[row_idx, y_flat] = b_pred_val
            p_b_corr = F.softmax(b_corr / temp, dim=-1)

            # Target-Corrected Super-Teacher distribution (swap t_pred and y_flat if Teacher is ever wrong)
            t_corr = t_clean.clone()
            t_y_val = t_clean[row_idx, y_flat]
            t_pred_val = t_clean[row_idx, t_pred]
            t_corr[row_idx, t_pred] = t_y_val
            t_corr[row_idx, y_flat] = t_pred_val
            p_t_corr = F.softmax(t_corr / temp, dim=-1)

        kl_b = F.kl_div(log_p_s, p_b, reduction="none").sum(dim=-1) * (temp * temp)
        kl_b_corr = F.kl_div(log_p_s, p_b_corr, reduction="none").sum(dim=-1) * (temp * temp)
        kl_t_corr = F.kl_div(log_p_s, p_t_corr, reduction="none").sum(dim=-1) * (temp * temp)

        z_y = s_flat.gather(dim=-1, index=y_flat.unsqueeze(-1)).squeeze(-1)
        z_b = s_flat.gather(dim=-1, index=b_pred.unsqueeze(-1)).squeeze(-1)

        # Prior-token self-copy / digit-boundary margin:
        # Penalize copying any distinct prior target token y_p (p < t, y_p != -100, y_p != y_t)
        with torch.no_grad():
            distinct_prior = (
                (shift_labels.unsqueeze(2) != shift_labels.unsqueeze(1))
                & tril_mask
                & valid_prior
                & valid_mask.unsqueeze(2)
            )  # [B, L, L]
            safe_prior_ids = torch.where(shift_labels > 0, shift_labels, torch.zeros_like(shift_labels))

        prior_logits_all = shift_s.float().gather(
            dim=-1,
            index=safe_prior_ids.unsqueeze(1).expand(-1, seq_len, -1),
        )
        prior_logits_masked = prior_logits_all.masked_fill(~distinct_prior, -1e9)
        prior_copy_max_2d, _ = prior_logits_masked.max(dim=-1)
        has_distinct_prior_2d = distinct_prior.any(dim=-1)
        has_distinct_prior = has_distinct_prior_2d[valid_mask]
        prior_copy_max = torch.where(
            has_distinct_prior,
            prior_copy_max_2d[valid_mask],
            torch.zeros_like(z_y),
        )
        prior_copy_margin = torch.where(
            has_distinct_prior,
            F.relu(self.self_copy_gamma - (z_y - prior_copy_max)),
            torch.zeros_like(z_y),
        )

        # Runner-up margin for 4-bit quantization (IQ4_NL_emb8) robustness
        s_other = s_flat.masked_fill(
            F.one_hot(y_flat, num_classes=s_flat.size(-1)).bool(),
            -1e9,
        )
        z_runner_up = s_other.max(dim=-1).values
        quant_margin = F.relu(2.4 - (z_y - z_runner_up))

        # EOS Anti-Hallucination Margin: enforce z(</s>) - max_{h in {172,248,4,469}} z(h) >= 6.0 at EOS
        z_halluc = s_flat[:, [172, 248, 4, 469]].max(dim=-1).values
        eos_margin = torch.where(is_eos, F.relu(6.0 - (z_y - z_halluc)), torch.zeros_like(z_y))

        # Contrastive Margin with Intra-Sequence Self-Copy Penalty boost (gamma=3.0 vs 2.2)
        gamma_vec = torch.where(
            is_self_copy,
            torch.full_like(z_y, self.self_copy_gamma),
            torch.full_like(z_y, self.margin_gamma),
        )
        margin_loss = F.relu(gamma_vec - (z_y - z_b))

        # Dual-Anchor Gating Loss (Measure 3):
        # 1) When Base agrees with Target: distill from both Teacher and Base, plus boundary/quantization margins
        loss_base_correct = (
            0.30 * ce_loss
            + 0.40 * kl_t_corr
            + 0.30 * kl_b
            + 0.15 * eos_margin
            + 0.20 * prior_copy_margin
            + 0.20 * quant_margin
        )

        # 2) When Base is wrong: Super-Teacher KL + Contrastive Margin + Target-Corrected Base KL
        loss_base_wrong = (
            0.45 * ce_loss
            + 0.25 * kl_t_corr
            + 0.05 * kl_b_corr
            + 0.25 * margin_loss
            + 0.15 * eos_margin
            + 0.20 * prior_copy_margin
            + 0.20 * quant_margin
        )

        token_loss = torch.where(base_agrees, loss_base_correct, loss_base_wrong)
        loss = token_loss.mean()

        return (loss, outputs) if return_outputs else loss


def save_merged_with_exact_base_metadata(
    student_dir: str,
    lora_dir: str,
    out_dir: str,
    alpha_blend: float = 1.0,
    fullwidth_digit_scale: float = 1.0,
):
    """Merge DoRA/LoRA weights with optional WiSE-FT interpolation and preserve exact base GGUF tokenizer files."""
    os.makedirs(out_dir, exist_ok=True)
    base_state = AutoModelForCausalLM.from_pretrained(
        student_dir, dtype=torch.float32, device_map="cpu"
    ).state_dict()

    base_for_peft = AutoModelForCausalLM.from_pretrained(
        student_dir, dtype=torch.float32, device_map="cpu"
    )
    merged_model = PeftModel.from_pretrained(base_for_peft, lora_dir).merge_and_unload()

    if alpha_blend < 1.0:
        with torch.no_grad():
            for name, param in merged_model.named_parameters():
                if name in base_state:
                    param.copy_((1.0 - alpha_blend) * base_state[name] + alpha_blend * param.data)

    if fullwidth_digit_scale != 1.0 and (Path(student_dir) / "vocab.json").exists():
        tok = AutoTokenizer.from_pretrained(student_dir, local_files_only=True)
        wte = merged_model.get_input_embeddings().weight
        with torch.no_grad():
            for ch in "０１２３４５６７８９":
                ids = tok.encode(ch, add_special_tokens=False)
                if len(ids) == 1:
                    wte.data[ids[0]] *= fullwidth_digit_scale

    merged_model.save_pretrained(out_dir)

    for fname in ("config.json", "vocab.json", "merges.txt", "tokenizer_config.json", "special_tokens_map.json", "provenance.json"):
        src = Path(student_dir) / fname
        if src.exists():
            shutil.copy2(src, Path(out_dir) / fname)


def convert_merged_to_gguf(merged_dir: str, gguf_path: str):
    """Convert HuggingFace merged model directory to GGUF F16 format."""
    convert_script = ROOT / "android" / "app" / "src" / "main" / "cpp" / "llama.cpp" / "convert_hf_to_gguf.py"
    if not convert_script.exists():
        raise FileNotFoundError(f"convert_hf_to_gguf.py not found at {convert_script}")

    os.makedirs(Path(gguf_path).parent, exist_ok=True)
    cmd = [
        sys.executable,
        str(convert_script),
        str(merged_dir),
        "--outfile", str(gguf_path),
        "--outtype", "f16",
    ]
    print(f"\nConverting to GGUF F16: {' '.join(cmd)}", flush=True)
    subprocess.check_call(cmd)
    print(f"GGUF F16 created successfully: {gguf_path} ({os.path.getsize(gguf_path) / 1024 / 1024:.2f} MB)", flush=True)


def build_tokenized_examples(raw_rows: list[dict], tokenizer, max_length: int, is_train: bool = True) -> list[dict]:
    """Tokenize dataset rows with exact EOS (id=3) and ASCII space alignment for both llama.cpp and ZenzContext."""
    tokenized = []
    eos_id = tokenizer.eos_token_id
    assert eos_id == 3, f"CRITICAL: Expected eos_token_id=3 (</s>), got {eos_id}"

    for row in raw_rows:
        prompt = row["prompt"]
        comp = row["completion"]
        prompts = [prompt, "\uee02" + prompt] if (is_train and prompt.startswith("\uee00")) else [prompt]

        for p_text in prompts:
            if " " in p_text or " " in comp:
                # Variant 1: llama.cpp raw ASCII space in prompt ([UNK]=0) -> full-width space (\u3000=567) in completion
                p_tok_unk = encode_with_space_token(tokenizer, p_text, SPACE_UNK_TOKEN_ID)
                c_tok_u3000 = encode_with_space_token(tokenizer, comp, SPACE_U3000_TOKEN_ID) + [eos_id]
                tokenized.append({
                    "input_ids": (p_tok_unk + c_tok_u3000)[:max_length],
                    "labels": ([-100] * len(p_tok_unk) + c_tok_u3000)[:max_length],
                })
                # Variant 2: ZenzContext.swift normalized \u3000 (567) in prompt -> \u3000 (567) in completion
                if is_train and " " in p_text:
                    p_tok_u3000 = encode_with_space_token(tokenizer, p_text, SPACE_U3000_TOKEN_ID)
                    tokenized.append({
                        "input_ids": (p_tok_u3000 + c_tok_u3000)[:max_length],
                        "labels": ([-100] * len(p_tok_u3000) + c_tok_u3000)[:max_length],
                    })
            else:
                p_tok = tokenizer.encode(p_text, add_special_tokens=False)
                c_tok = tokenizer.encode(comp, add_special_tokens=False) + [eos_id]
                tokenized.append({
                    "input_ids": (p_tok + c_tok)[:max_length],
                    "labels": ([-100] * len(p_tok) + c_tok)[:max_length],
                })

    return tokenized


def main():
    args = parse_args()

    train_raw, val_raw = checked_datasets(args.train_file, args.val_file, args.output_dir)
    print(f"Dataset guard passed: train={len(train_raw)}, val={len(val_raw)}", flush=True)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32

    print(f"Loading Student Base Nano model: {args.student_model} on {device} ({dtype})...", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(args.student_model)
    assert tokenizer.eos_token_id == 3, f"Expected eos_token_id=3 (</s>), got {tokenizer.eos_token_id}"
    assert tokenizer.bos_token_id == 2, f"Expected bos_token_id=2 (<s>), got {tokenizer.bos_token_id}"
    if tokenizer.pad_token is None:
        tokenizer.pad_token = "[PAD]"

    student_model = AutoModelForCausalLM.from_pretrained(
        args.student_model,
        dtype=dtype,
        attn_pdrop=0.0,
        embd_pdrop=0.0,
        resid_pdrop=0.0,
        summary_first_dropout=0.0,
    ).to(device)
    disable_all_dropout(student_model)

    print(f"Loading Super-Teacher (Standard v3) model: {args.teacher_model} on {device} ({dtype})...", flush=True)
    teacher_model = AutoModelForCausalLM.from_pretrained(
        args.teacher_model,
        dtype=dtype,
    ).to(device)
    disable_all_dropout(teacher_model)
    teacher_model.eval()
    for param in teacher_model.parameters():
        param.requires_grad = False

    print(f"Configuring 6-Layer High-Rank DoRA (r={args.lora_r}, alpha={args.lora_alpha}, dora={args.use_dora})...", flush=True)
    lora_config = LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        target_modules=["c_attn", "c_proj", "c_fc"],
        use_dora=args.use_dora,
        bias="none",
    )
    student_model = get_peft_model(student_model, lora_config)
    disable_all_dropout(student_model)
    student_model.print_trainable_parameters()

    train_tokenized = build_tokenized_examples(train_raw, tokenizer, args.max_length, is_train=True)
    val_tokenized = build_tokenized_examples(val_raw, tokenizer, args.max_length, is_train=False)
    print(f"Tokenized instances: train={len(train_tokenized)}, val={len(val_tokenized)}", flush=True)

    train_ds = Dataset.from_list(train_tokenized)
    val_ds = Dataset.from_list(val_tokenized)

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=20,
        weight_decay=0.01,
        eval_strategy="epoch",
        save_strategy="no",
        logging_steps=20,
        bf16=torch.cuda.is_available(),
        fp16=False,
        seed=42,
        report_to="none",
    )

    collator = DataCollatorForSeq2Seq(
        tokenizer,
        pad_to_multiple_of=8,
        return_tensors="pt",
        padding=True,
    )

    trainer = SuperTeacherDualAnchorTrainer(
        teacher_model=teacher_model,
        temperature=args.temperature,
        margin_gamma=args.margin_gamma,
        self_copy_gamma=args.self_copy_gamma,
        model=student_model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=collator,
    )

    print("\nStarting Super-Teacher Dual-Anchor Margin Distillation (0% dropout, 6-Layer DoRA r=64)...", flush=True)
    trainer.train()

    print(f"\nSaving final DoRA adapter to {args.output_dir}...", flush=True)
    trainer.save_model(args.output_dir)
    record_training_completed(args.output_dir)

    if args.merged_dir:
        for blend, suffix in ((1.0, ""), (0.85, "-blend85")):
            target_dir = f"{args.merged_dir}{suffix}"
            print(f"\nMerging DoRA (blend={blend}) -> {target_dir}...", flush=True)
            save_merged_with_exact_base_metadata(
                args.student_model, args.output_dir, target_dir, alpha_blend=blend
            )
            src = Path(args.output_dir) / "dataset-provenance.json"
            if src.exists():
                shutil.copy2(src, Path(target_dir) / "dataset-provenance.json")

            gguf_name = f"{args.run_name}-f16.gguf" if blend == 1.0 else f"{args.run_name}-blend85-f16.gguf"
            f16_gguf = str(ROOT / "artifacts" / "models" / gguf_name)
            convert_merged_to_gguf(target_dir, f16_gguf)


if __name__ == "__main__":
    main()
