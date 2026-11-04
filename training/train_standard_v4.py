"""Continue Standard v3 with Nano v4's corrected dual-anchor loss and rank-64 DoRA.

The frozen Nano teacher supplies complementary knowledge; disabling the adapter
anchors the student to Standard v3. Evaluation sets never enter the training loss.
"""

import argparse
import json
import shutil
from pathlib import Path

import torch
from datasets import Dataset
from peft import LoraConfig, TaskType, get_peft_model
from transformers import (
    AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq,
    TrainingArguments, set_seed,
)

from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import sha256
from train_nano_v3 import (
    SuperTeacherDualAnchorTrainer, build_tokenized_examples,
    convert_merged_to_gguf, disable_all_dropout, save_merged_with_exact_base_metadata,
)


def layer_tier(name):
    for index in range(12):
        if f".h.{index}." in name:
            return "low" if index < 4 else "mid" if index < 8 else "high"
    return "mid"


class StandardDualAnchorTrainer(SuperTeacherDualAnchorTrainer):
    def create_optimizer(self):
        if self.optimizer is not None:
            return self.optimizer
        groups = {}
        for name, parameter in self.model.named_parameters():
            if not parameter.requires_grad:
                continue
            tier = layer_tier(name)
            no_decay = "bias" in name or "lora_magnitude_vector" in name or parameter.ndim < 2
            key = (tier, no_decay)
            if key not in groups:
                groups[key] = {
                    "params": [],
                    "lr": self.args.learning_rate * {"low": 0.4, "mid": 1.0, "high": 1.3}[tier],
                    "weight_decay": 0.0 if no_decay else self.args.weight_decay,
                }
            groups[key]["params"].append(parameter)
        cls, kwargs = self.get_optimizer_cls_and_kwargs(self.args, self.model)
        self.optimizer = cls(list(groups.values()), **kwargs)
        return self.optimizer


def validate_tokenizers(student, teacher):
    if student.get_vocab() != teacher.get_vocab():
        raise ValueError("Teacher and student token IDs must match for logit distillation")
    for tokenizer in (student, teacher):
        if (tokenizer.bos_token_id, tokenizer.eos_token_id, tokenizer.pad_token_id) != (2, 3, 1):
            raise ValueError("Expected BOS=2, EOS=3, PAD=1")
    probes = ("\uee02文脈\uee00カナ\uee01", "ＡＢＣ　５０％", "変換する</s>")
    for text in probes:
        if student.encode(text) != teacher.encode(text):
            raise ValueError("Teacher and student tokenization must match")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--student-model", default="artifacts/checkpoints/standard-v3-argon4-r2-merged")
    parser.add_argument("--teacher-model", default="artifacts/checkpoints/nano-v4-argon4-merged")
    parser.add_argument("--train-file", default="artifacts/datasets/standard-v3-knowledge/train.jsonl")
    parser.add_argument("--val-file", default="artifacts/datasets/standard-v3-knowledge/val.jsonl")
    parser.add_argument("--run-name", default="standard-v4-nano-transfer")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--lr", type=float, default=8e-5)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    if Path(args.run_name).name != args.run_name:
        parser.error("run-name must be a single file name")
    output = Path("artifacts/checkpoints") / f"{args.run_name}-lora"
    merged = Path("artifacts/checkpoints") / f"{args.run_name}-merged"
    gguf = Path("artifacts/models") / f"{args.run_name}-f16.gguf"
    if merged.exists() or gguf.exists():
        raise FileExistsError("Use a fresh run name; existing model artifacts are preserved")
    set_seed(args.seed)
    tokenizer = AutoTokenizer.from_pretrained(args.student_model, local_files_only=True)
    teacher_tokenizer = AutoTokenizer.from_pretrained(args.teacher_model, local_files_only=True)
    validate_tokenizers(tokenizer, teacher_tokenizer)
    train, val = checked_datasets(args.train_file, args.val_file, output)
    # Store the exact local model weights, tokenizer and training-code identities.
    manifest = {"arguments": vars(args), "models": {}, "code": {}}
    for role, directory in (("student", args.student_model), ("teacher", args.teacher_model)):
        manifest["models"][role] = {
            str(p): sha256(p) for p in sorted(Path(directory).iterdir())
            if p.is_file() and p.suffix in (".json", ".safetensors", ".bin", ".txt")
        }
    for name in ("train_standard_v4.py", "train_nano_v3.py", "dataset_guard.py"):
        path = Path(__file__).parent / name
        manifest["code"][name] = sha256(path)
    (output / "run-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    bf16 = device == "cuda" and torch.cuda.is_bf16_supported()
    dtype = torch.bfloat16 if bf16 else torch.float32
    student = AutoModelForCausalLM.from_pretrained(args.student_model, dtype=dtype, local_files_only=True).to(device)
    if student.config.n_layer != 12:
        raise ValueError("Standard student must have 12 layers")
    teacher = AutoModelForCausalLM.from_pretrained(args.teacher_model, dtype=dtype, local_files_only=True).to(device)
    teacher.eval().requires_grad_(False)
    student = get_peft_model(student, LoraConfig(
        task_type=TaskType.CAUSAL_LM, r=64, lora_alpha=128, lora_dropout=0.0,
        target_modules=["c_attn", "c_proj", "c_fc"], use_dora=True, bias="none",
    ))
    disable_all_dropout(student)
    disable_all_dropout(teacher)
    student.print_trainable_parameters()
    train_tokens = build_tokenized_examples(train, tokenizer, 128, is_train=True)
    val_tokens = build_tokenized_examples(val, tokenizer, 128, is_train=False)
    for example in train_tokens + val_tokens:
        if example["labels"][-1] != 3:
            raise ValueError("Truncation removed EOS; increase max_length before training")
    print(f"Dataset: {len(train)} train / {len(val)} val; {len(train_tokens)} training sequences", flush=True)
    trainer = StandardDualAnchorTrainer(
        teacher_model=teacher, temperature=2.0, margin_gamma=2.2, self_copy_gamma=3.0,
        model=student,
        args=TrainingArguments(
            output_dir=str(output), num_train_epochs=args.epochs,
            per_device_train_batch_size=args.batch_size, per_device_eval_batch_size=args.batch_size,
            learning_rate=args.lr, lr_scheduler_type="cosine", warmup_steps=20,
            weight_decay=0.01, eval_strategy="epoch", save_strategy="epoch",
            save_total_limit=1, load_best_model_at_end=True, metric_for_best_model="eval_loss",
            greater_is_better=False, logging_steps=20, bf16=bf16, seed=args.seed,
            report_to="none", disable_tqdm=True,
        ),
        train_dataset=Dataset.from_list(train_tokens), eval_dataset=Dataset.from_list(val_tokens),
        data_collator=DataCollatorForSeq2Seq(tokenizer, pad_to_multiple_of=8, padding=True),
    )
    trainer.train()
    trainer.save_model(str(output))
    trainer.state.save_to_json(str(output / "trainer_state.json"))
    record_training_completed(output)
    # Merge once at full strength; select epoch only on held-out validation loss.
    save_merged_with_exact_base_metadata(args.student_model, str(output), str(merged))
    for name in ("dataset-provenance.json", "run-manifest.json", "trainer_state.json"):
        shutil.copy2(output / name, merged / name)
    convert_merged_to_gguf(str(merged), str(gguf))


if __name__ == "__main__":
    main()
