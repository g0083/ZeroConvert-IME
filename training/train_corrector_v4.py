"""Nano v4 -> surface corrector, preserving the v3.2 corrector's prompt contract.

Keep the r16/alpha32/3-epoch recipe. Use FP32 frozen base weights with BF16
autocast (avoids rounding the Nano parent permanently), effective batch 32,
and select on balanced clean/typo greedy validation, never benchmark answers.
"""
import argparse
import hashlib
import json
from pathlib import Path

import torch
from datasets import Dataset
from peft import LoraConfig, TaskType, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments, set_seed

from build_corrector_v4_dataset import is_clean
from corrector_encoding import encode_example
from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import sha256


def validation_cohort(rows, per_kind=128):
    """Equal clean/typo weight, at most one example of each kind per source group."""
    ordered = sorted(rows, key=lambda r: (
        hashlib.sha256((r["prompt"] + r["completion"]).encode()).hexdigest(), r["source_group"]))
    selected = []
    for clean in (True, False):
        seen = set()
        for row in ordered:
            if is_clean(row) == clean and row["source_group"] not in seen:
                selected.append({**row, "validation_clean": clean})
                seen.add(row["source_group"])
                if len(seen) == per_kind:
                    break
        if not seen:
            raise ValueError("Validation requires both clean and typo source groups")
    return selected


class GenerationTrainer(Trainer):
    def __init__(self, *args, generation_rows, generation_tokenizer, **kwargs):
        super().__init__(*args, **kwargs)
        self.generation_rows = generation_rows
        self.generation_tokenizer = generation_tokenizer

    def evaluate(self, *args, **kwargs):
        metrics = super().evaluate(*args, **kwargs)
        tokenizer = self.generation_tokenizer
        was_training = self.model.training
        previous_side = tokenizer.padding_side
        tokenizer.padding_side = "left"
        self.model.eval()
        details = []
        try:
            with torch.inference_mode(), self.accelerator.autocast():
                for start in range(0, len(self.generation_rows), 32):
                    rows = self.generation_rows[start:start + 32]
                    prompts = []
                    for row in rows:
                        encoded = encode_example(tokenizer, row["prompt"], row["completion"], 256)
                        n = next(i for i, label in enumerate(encoded["labels"]) if label != -100)
                        prompts.append({"input_ids": encoded["input_ids"][:n], "attention_mask": [1] * n})
                    batch = tokenizer.pad(prompts, padding=True, return_tensors="pt").to(self.model.device)
                    generated = self.model.generate(**batch, do_sample=False, max_new_tokens=96,
                                                    eos_token_id=3, pad_token_id=1, use_cache=True)
                    for row, tokens in zip(rows, generated[:, batch["input_ids"].shape[1]:].tolist()):
                        eos = 3 in tokens
                        answer = tokens[:tokens.index(3)] if eos else tokens
                        output = tokenizer.decode(answer, skip_special_tokens=False)
                        details.append({"prompt": row["prompt"], "expected": row["completion"], "output": output,
                                        "clean": row["validation_clean"], "eos": eos,
                                        "correct": eos and output == row["completion"]})
        finally:
            tokenizer.padding_side = previous_side
            self.model.train(was_training)
        scores = {}
        for clean, name in ((True, "clean"), (False, "typo")):
            rows = [r for r in details if r["clean"] == clean]
            scores[name] = {"total": len(rows), "correct": sum(r["correct"] for r in rows)}
            metrics[f"eval_{name}_exact"] = scores[name]["correct"] / len(rows)
        metrics["eval_balanced_exact"] = (metrics["eval_clean_exact"] + metrics["eval_typo_exact"]) / 2
        path = Path(self.args.output_dir) / f"generation-step{self.state.global_step}.json"
        path.write_text(json.dumps({"scores": scores, "metrics": metrics, "cases": details}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        self.log({k: v for k, v in metrics.items() if "exact" in k})
        return metrics


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", default="corrector-v4-nano-20261030-r1")
    p.add_argument("--base-model", default="artifacts/checkpoints/nano-v4-argon4-merged")
    p.add_argument("--dataset", default="artifacts/datasets/corrector-v4-20261030")
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--lr", type=float, default=2e-4)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    if Path(args.run_name).name != args.run_name:
        raise ValueError("run-name must be one directory component")
    output = Path("artifacts/checkpoints") / f"{args.run_name}-lora"
    base = Path(args.base_model)
    dataset = Path(args.dataset)
    train, val = checked_datasets(dataset / "train.jsonl", dataset / "val.jsonl", output)
    set_seed(args.seed)
    torch.set_num_threads(4)
    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True)
    if (tokenizer.bos_token_id, tokenizer.eos_token_id, tokenizer.pad_token_id) != (2, 3, 1):
        raise ValueError("Expected zenz BOS/EOS/PAD = 2/3/1")
    cohort = validation_cohort(val)
    def encode(rows):
        return Dataset.from_list([encode_example(tokenizer, r["prompt"], r["completion"], 256) for r in rows])
    train_tokens, val_tokens = encode(train), encode(val)
    manifest = {"arguments": vars(args), "base": {p.name: sha256(p) for p in sorted(base.iterdir())
                if p.is_file() and p.suffix in (".json", ".safetensors")},
                "dataset_manifest_sha256": sha256(dataset / "manifest.json"),
                "source": {str(p): sha256(p) for p in [Path(__file__), Path("training/corrector_encoding.py"),
                            Path("training/dataset_guard.py"), Path("data/build_corrector_v4_dataset.py")]},
                "recipe": {"r": 16, "alpha": 32, "dropout": .05, "max_length": 256,
                           "batch": 32, "accum": 1, "selection": "balanced clean/typo greedy exact, 1 per group/kind"},
                "validation_cohort": [{"prompt": r["prompt"], "completion": r["completion"],
                                       "clean": r["validation_clean"]} for r in cohort]}
    (output / "run-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    # Full precision parent is frozen; autocast accelerates matmuls without
    # permanently quantizing the inherited Nano v4 weights before LoRA merging.
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.float32, local_files_only=True).to(device)
    if model.config.n_layer != 6 or model.config.n_embd != 512:
        raise ValueError("Expected Nano v4's six-layer, 512-dimensional model")
    model = get_peft_model(model, LoraConfig(task_type=TaskType.CAUSAL_LM, r=16, lora_alpha=32,
                                          lora_dropout=.05, target_modules=["c_attn", "c_proj"], bias="none"))
    model.print_trainable_parameters()
    bf16 = device == "cuda" and torch.cuda.is_bf16_supported()
    trainer = GenerationTrainer(model=model, generation_rows=cohort, generation_tokenizer=tokenizer,
        args=TrainingArguments(output_dir=str(output), per_device_train_batch_size=32,
            per_device_eval_batch_size=32, gradient_accumulation_steps=1,
            num_train_epochs=args.epochs, learning_rate=args.lr, seed=args.seed, data_seed=args.seed,
            eval_strategy="epoch", save_strategy="epoch", save_total_limit=2,
            load_best_model_at_end=True, metric_for_best_model="eval_balanced_exact", greater_is_better=True,
            bf16=bf16, fp16=device == "cuda" and not bf16,
            logging_steps=100, report_to="none", disable_tqdm=True),
        train_dataset=train_tokens, eval_dataset=val_tokens,
        data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, pad_to_multiple_of=8))
    trainer.train()
    trainer.save_model(str(output))
    tokenizer.save_pretrained(output)
    trainer.state.save_to_json(str(output / "trainer_state.json"))
    record_training_completed(output)
    print(f"Selected {trainer.state.best_model_checkpoint}: {trainer.state.best_metric}", flush=True)


if __name__ == "__main__":
    main()
