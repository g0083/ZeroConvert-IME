"""Domain-teacher dual anchors, r64 DoRA and error-focused loss for corrector v4.

Uses the existing surface contract. Frozen teachers and adapter-disabled anchors
never receive gradients; copying is penalized only when it disagrees with gold.
"""
import argparse
import json
from pathlib import Path

import torch
import torch.nn.functional as F
from datasets import Dataset
from peft import LoraConfig, TaskType, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, TrainingArguments, set_seed

from build_corrector_v4_dataset import is_clean
from corrector_encoding import encode_example
from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import load_jsonl, sha256, source_keys
from train_corrector_v4 import GenerationTrainer, validation_cohort
from train_nano_v3 import disable_all_dropout


def corrected_logits(logits, labels):
    """Nano v4's target correction: swap the teacher argmax and gold logit."""
    logits = logits.clone()
    predicted = logits.argmax(-1)
    index = torch.arange(len(labels), device=labels.device)
    gold = logits[index, labels].clone()
    top = logits[index, predicted].clone()
    logits[index, predicted] = gold
    logits[index, labels] = top
    return logits


class DualAnchorCorrectorTrainer(GenerationTrainer):
    def __init__(self, *args, teacher_model, **kwargs):
        super().__init__(*args, **kwargs)
        # This objective returns a weighted microbatch mean, not a loss already
        # divided by num_items_in_batch across the accumulation window.
        self.model_accepts_loss_kwargs = False
        self.loss_is_scaled_for_ga = False
        self.teacher_model = teacher_model.to(self.model.device).eval().requires_grad_(False)

    def create_optimizer(self):
        if self.optimizer is not None:
            return self.optimizer
        groups = {}
        for name, parameter in self.model.named_parameters():
            if not parameter.requires_grad:
                continue
            tier = next((i // 2 for i in range(6) if f".h.{i}." in name), 1)
            no_decay = parameter.ndim < 2 or "bias" in name or "lora_magnitude_vector" in name
            group = groups.setdefault((tier, no_decay), {"params": [],
                "lr": self.args.learning_rate * (0.45, 1., 1.35)[tier],
                "weight_decay": 0. if no_decay else self.args.weight_decay})
            group["params"].append(parameter)
        cls, kwargs = self.get_optimizer_cls_and_kwargs(self.args, self.model)
        self.optimizer = cls(list(groups.values()), **kwargs)
        return self.optimizer

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        labels = inputs["labels"][:, 1:]
        forward = {key: inputs[key] for key in ("input_ids", "attention_mask") if key in inputs}
        outputs = model(**forward)
        student = outputs.logits[:, :-1]
        valid = labels != -100
        if not valid.any():
            loss = student.sum() * 0.
            return (loss, outputs) if return_outputs else loss
        with torch.no_grad():
            teacher = self.teacher_model(**forward).logits[:, :-1][valid].float()
            with model.disable_adapter():
                anchor = model(**forward).logits[:, :-1][valid].float()
        s, y = student[valid].float(), labels[valid]
        ce = F.cross_entropy(s, y, reduction="none")
        temperature = 2.
        logp = F.log_softmax(s / temperature, dim=-1)
        with torch.no_grad():
            anchor_agrees = anchor.argmax(-1) == y
            teacher_prob = F.softmax(corrected_logits(teacher, y) / temperature, dim=-1)
            # Preserve the anchor where correct; correct it where labels disagree.
            anchor_prob = F.softmax(corrected_logits(anchor, y) / temperature, dim=-1)
        teacher_kl = F.kl_div(logp, teacher_prob, reduction="none").sum(-1) * temperature**2
        anchor_kl = F.kl_div(logp, anchor_prob, reduction="none").sum(-1) * temperature**2
        gold_z = s.gather(-1, y[:, None]).squeeze(-1)
        wrong_z = s.gather(-1, anchor.argmax(-1)[:, None]).squeeze(-1)
        margin = torch.where(anchor_agrees, 0., F.relu(2.2 - gold_z + wrong_z))
        # EOS anti-hallucination, as in Nano v4. Do not suppress punctuation when
        # it is itself supervised; the margin is applied at gold EOS only.
        anti_ids = [i for i in (172, 248, 4, 469) if i < s.shape[-1]]
        eos_margin = torch.where(y == 3, F.relu(6. - gold_z + s[:, anti_ids].max(-1).values), 0.)
        token_loss = .65 * ce + .20 * teacher_kl + .15 * anchor_kl + .15 * margin + .10 * eos_margin
        # Focus on inherited mistakes without punishing legitimate unchanged
        # output on clean inputs. Normal examples also receive extra protection.
        clean = inputs["clean_target"][:, None].expand_as(labels)[valid]
        weights = torch.where(anchor_agrees, 1., 2.) * torch.where(clean.bool(), 1.5, 1.)
        loss = (token_loss * weights).sum() / weights.sum()
        return (loss, outputs) if return_outputs else loss


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", default="corrector-v4-advanced-20261030-dora64")
    p.add_argument("--base-model", default="artifacts/checkpoints/corrector-v4-nano-20261030-r1-merged")
    p.add_argument("--teacher-model", default="artifacts/checkpoints/corrector-size-small-20261027-merged")
    p.add_argument("--dataset", default="artifacts/datasets/corrector-v4-surface-20261030")
    p.add_argument("--epochs", type=int, default=2)
    p.add_argument("--lr", type=float, default=8e-5)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    if Path(args.run_name).name != args.run_name:
        raise ValueError("run-name must be a single component")
    output = Path("artifacts/checkpoints") / f"{args.run_name}-lora"
    dataset = Path(args.dataset)
    base, teacher_path = Path(args.base_model), Path(args.teacher_model)
    # The domain teacher was trained on the same original train split. Check
    # its known training exposure against validation before allocating models.
    teacher_provenance = json.loads((teacher_path.with_name(teacher_path.name.replace("-merged", "-lora")) / "dataset-provenance.json").read_text(encoding="utf-8"))
    teacher_train = load_jsonl(teacher_provenance["train"]["path"])
    if sha256(teacher_provenance["train"]["path"]) != teacher_provenance["train"]["sha256"]:
        raise ValueError("Teacher training source changed")
    teacher_keys = set().union(*(source_keys(r) for r in teacher_train))
    val_rows = load_jsonl(dataset / "val.jsonl")
    if any(source_keys(r) & teacher_keys for r in val_rows):
        raise ValueError("Domain teacher was exposed to validation sources")
    train, val = checked_datasets(dataset / "train.jsonl", dataset / "val.jsonl", output)
    set_seed(args.seed)
    torch.set_num_threads(4)
    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True)
    teacher_tokenizer = AutoTokenizer.from_pretrained(teacher_path, local_files_only=True)
    if tokenizer.get_vocab() != teacher_tokenizer.get_vocab() or (tokenizer.bos_token_id, tokenizer.eos_token_id, tokenizer.pad_token_id) != (2, 3, 1):
        raise ValueError("Exact tokenizer IDs are required")
    manifest = {"arguments": vars(args), "base": {p.name: sha256(p) for p in base.iterdir()
                if p.is_file() and p.suffix in (".json", ".safetensors")},
                "teacher": {p.name: sha256(p) for p in teacher_path.iterdir() if p.is_file() and p.suffix in (".json", ".safetensors")},
                "teacher_provenance": teacher_provenance, "teacher_training_validation_overlap": 0,
                "dataset_manifest_sha256": sha256(dataset / "manifest.json"),
                "source": {str(p): sha256(p) for p in [Path(__file__), Path("training/train_corrector_v4.py"), Path("training/corrector_encoding.py")]},
                "recipe": {"r": 64, "alpha": 128, "dora": True, "modules": ["c_attn", "c_proj", "c_fc"],
                           "batch": 16, "accum": 2, "dropout": 0, "temperature": 2.,
                           "microbatch_loss_already_scaled_for_accumulation": False,
                           "loss": "target-corrected domain teacher + frozen corrector anchor + error-focused CE/margin + EOS margin + clean protection"}}
    cohort = validation_cohort(val)
    manifest["validation_cohort"] = [{"prompt": r["prompt"], "completion": r["completion"], "clean": r["validation_clean"]} for r in cohort]
    (output / "run-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    bf16 = device == "cuda" and torch.cuda.is_bf16_supported()
    model = AutoModelForCausalLM.from_pretrained(base, local_files_only=True, dtype=torch.float32).to(device)
    model = get_peft_model(model, LoraConfig(task_type=TaskType.CAUSAL_LM, r=64, lora_alpha=128,
        target_modules=["c_attn", "c_proj", "c_fc"], use_dora=True, lora_dropout=0., bias="none"))
    teacher = AutoModelForCausalLM.from_pretrained(teacher_path, local_files_only=True,
        dtype=torch.bfloat16 if bf16 else torch.float32).to(device)
    disable_all_dropout(model)
    disable_all_dropout(teacher)
    model.print_trainable_parameters()
    def encode(rows):
        return Dataset.from_list([{**encode_example(tokenizer, r["prompt"], r["completion"], 256), "clean_target": is_clean(r)} for r in rows])
    collator = DataCollatorForSeq2Seq(tokenizer=tokenizer, pad_to_multiple_of=8)
    def collate(rows):
        clean = torch.tensor([r["clean_target"] for r in rows])
        batch = collator([{k: v for k, v in r.items() if k != "clean_target"} for r in rows])
        batch["clean_target"] = clean
        return batch
    trainer = DualAnchorCorrectorTrainer(model=model, teacher_model=teacher,
        generation_rows=cohort, generation_tokenizer=tokenizer,
        args=TrainingArguments(output_dir=str(output), num_train_epochs=args.epochs, learning_rate=args.lr,
            per_device_train_batch_size=16, per_device_eval_batch_size=16, gradient_accumulation_steps=2,
            warmup_steps=30, lr_scheduler_type="cosine", weight_decay=.01,
            remove_unused_columns=False, eval_strategy="epoch", save_strategy="epoch", save_total_limit=2,
            load_best_model_at_end=True, metric_for_best_model="eval_balanced_exact", greater_is_better=True,
            bf16=bf16, fp16=device == "cuda" and not bf16, seed=args.seed, data_seed=args.seed,
            logging_steps=100, report_to="none", disable_tqdm=True),
        train_dataset=encode(train), eval_dataset=encode(val), data_collator=collate)
    trainer.train()
    trainer.save_model(str(output))
    tokenizer.save_pretrained(output)
    trainer.state.save_to_json(str(output / "trainer_state.json"))
    record_training_completed(output)
    print("Selected", trainer.state.best_model_checkpoint, trainer.state.best_metric, flush=True)


if __name__ == "__main__":
    main()
