"""QWERTY-only native-observation training; select through real correction policy."""
import argparse
import json
from pathlib import Path

import torch
import torch.nn.functional as F
from datasets import Dataset
from peft import LoraConfig, TaskType, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments, set_seed

from corrector_encoding import encode_example
from dataset_guard import checked_datasets, record_training_completed
from dataset_isolation import load_jsonl, sha256, validate_rows, evaluation_keys
from qwerty_corrector_policy import replay_responses
from train_nano_v3 import disable_all_dropout
from qwerty_input_contract import model_prompt


class QwertyTrainer(Trainer):
    def __init__(self, *args, fixture, tokenizer_for_generation, baseline, clean_anchor=0., input_contract="kana", **kwargs):
        super().__init__(*args, **kwargs)
        self.model_accepts_loss_kwargs = False
        self.loss_is_scaled_for_ga = False
        self.fixture = fixture
        self.generation_tokenizer = tokenizer_for_generation
        self.baseline = baseline
        self.clean_anchor = clean_anchor
        self.input_contract = input_contract

    def create_optimizer(self):
        if self.optimizer is not None:
            return self.optimizer
        groups = {}
        for name, parameter in self.model.named_parameters():
            if not parameter.requires_grad:
                continue
            tier = next((i//2 for i in range(6) if f".h.{i}." in name), 1)
            no_decay = parameter.ndim < 2 or "lora_magnitude_vector" in name or "bias" in name
            group = groups.setdefault((tier, no_decay), {"params": [],
                "lr": self.args.learning_rate * (.5, 1., 1.25)[tier],
                "weight_decay": 0. if no_decay else self.args.weight_decay})
            group["params"].append(parameter)
        cls, kwargs = self.get_optimizer_cls_and_kwargs(self.args, self.model)
        self.optimizer = cls(list(groups.values()), **kwargs)
        return self.optimizer

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        forward = {k: inputs[k] for k in ("input_ids", "attention_mask") if k in inputs}
        output = model(**forward)
        logits, labels = output.logits[:, :-1].float(), inputs["labels"][:, 1:]
        valid = labels != -100
        ce = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), labels.reshape(-1), ignore_index=-100, reduction="none").reshape_as(labels)
        loss = ((ce * valid).sum(-1) / valid.sum(-1).clamp_min(1)).mean()
        clean = inputs.get("preserve")
        if self.clean_anchor and clean is not None and clean.any():
            with torch.no_grad(), model.disable_adapter():
                anchor = model(**{k:v[clean] for k,v in forward.items()}).logits[:, :-1].float()
            gold = labels[clean]
            agreed = (gold != -100) & (anchor.argmax(-1) == gold)
            if agreed.any():
                s = logits[clean][agreed]
                b = anchor[agreed]
                loss = loss + self.clean_anchor * F.kl_div(F.log_softmax(s/2.,-1), F.softmax(b/2.,-1), reduction="batchmean") * 4.
        eos = labels == 3
        if eos.any():
            s = logits[eos]
            anti = [i for i in (172, 248, 4, 469) if i < s.shape[-1]]
            loss = loss + .05 * F.relu(6. - s[:, 3] + s[:, anti].max(-1).values).mean()
        return (loss, output) if return_outputs else loss

    def evaluate(self, *args, **kwargs):
        metrics = super().evaluate(*args, **kwargs)
        requests = [(row["id"], call) for row in self.fixture["plan"]["cases"] for call in row["calls"]]
        tokenizer = self.generation_tokenizer
        old_side, was_training = tokenizer.padding_side, self.model.training
        tokenizer.padding_side = "left"
        self.model.eval()
        responses = {}
        observations = {r["id"]: r for r in self.fixture["cases"]}
        try:
            with torch.inference_mode(), self.accelerator.autocast():
                for start in range(0, len(requests), 32):
                    chunk = requests[start:start+32]
                    batch = tokenizer([model_prompt(call["prompt"], observations[case_id], self.input_contract) for case_id, call in chunk], add_special_tokens=False,
                                      padding=True, return_tensors="pt").to(self.model.device)
                    budgets = [call["request"]["n_predict"] for _, call in chunk]
                    generated = self.model.generate(**batch, do_sample=False, max_new_tokens=max(budgets),
                                                    eos_token_id=3, pad_token_id=1, use_cache=True)
                    for (case_id, call), values, budget in zip(chunk, generated[:, batch["input_ids"].shape[1]:].tolist(), budgets):
                        values = values[:budget]
                        ended = 3 in values
                        answer = values[:values.index(3)] if ended else values
                        responses[(case_id, call["prompt"])] = {"content": tokenizer.decode(answer, skip_special_tokens=False),
                            "stop_type": "eos" if ended else "limit", "stopped_eos": ended,
                            "tokens_predicted": len(answer)+int(ended)}
        finally:
            tokenizer.padding_side = old_side
            self.model.train(was_training)
        result = replay_responses(self.fixture, responses)
        summary = result["summary"]
        # Enforce the previous model's normal/pending behavior as validation caps.
        excess = sum(max(0, summary.get(k, 0)-self.baseline.get(k, 0)) for k in ("keep_proposed", "wait_proposed", "wrong_auto_applied", "preservation_proposed"))
        score = summary["case_f0_5"] if excess == 0 else -1. - excess
        metrics["eval_policy_score"] = score
        metrics["eval_policy_correct"] = summary.get("repair_correct", 0)
        metrics["eval_policy_normal_proposed"] = summary.get("keep_proposed", 0)
        metrics["eval_policy_wait_proposed"] = summary.get("wait_proposed", 0)
        path = Path(self.args.output_dir) / f"policy-step{self.state.global_step}.json"
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        self.log({k: v for k, v in metrics.items() if "policy" in k})
        return metrics


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", required=True)
    p.add_argument("--base-model", default="artifacts/checkpoints/nano-v4-argon4-merged")
    p.add_argument("--dataset", default="artifacts/datasets/corrector-qwerty-native-20261030-r1")
    p.add_argument("--fixture", required=True)
    p.add_argument("--baseline", required=True)
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--lr", type=float, default=1.2e-4)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--clean-anchor", type=float, default=0.)
    p.add_argument("--input-contract", choices=("kana", "raw-state"), default="kana")
    args = p.parse_args()
    if Path(args.run_name).name != args.run_name:
        raise ValueError("run-name must be one component")
    output = Path("artifacts/checkpoints") / f"{args.run_name}-lora"
    dataset, base = Path(args.dataset), Path(args.base_model)
    dm = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    for split in ("train", "val", "test"):
        if sha256(dataset / f"{split}.jsonl") != dm["outputs"][split]["sha256"]:
            raise ValueError("Dataset changed")
    fixture = json.loads(Path(args.fixture).read_text(encoding="utf-8"))
    baseline_report = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    if fixture["split"] != "val" or fixture["dataset_manifest_sha256"] != sha256(dataset / "manifest.json"):
        raise ValueError("Selection requires the matching validation fixture")
    if baseline_report["fixture_sha256"] != sha256(args.fixture):
        raise ValueError("Baseline fixture mismatch")
    train, val = checked_datasets(dataset / "train.jsonl", dataset / "val.jsonl", output)
    validate_rows(train, val + load_jsonl(dataset / "test.jsonl"), evaluation_keys())
    set_seed(args.seed)
    torch.set_num_threads(4)
    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True)
    if (tokenizer.bos_token_id, tokenizer.eos_token_id, tokenizer.pad_token_id) != (2, 3, 1):
        raise ValueError("Expected zenz tokenizer IDs")
    manifest = {"arguments": vars(args), "base": {p.name: sha256(p) for p in base.iterdir()
        if p.is_file() and p.suffix in (".json", ".safetensors")}, "dataset_manifest_sha256": sha256(dataset / "manifest.json"),
        "validation_fixture_sha256": sha256(args.fixture), "validation_baseline": baseline_report["summary"],
        "source": {p: sha256(p) for p in [str(Path(__file__)), "training/qwerty_input_contract.py", "training/corrector_encoding.py", "benchmarks/research/qwerty_corrector_policy.py", "windows/crates/server/src/ai_assist/corrector.rs", "engine/rust/examples/evaluate_corrector.rs"]},
        "recipe": {"rank": 32, "alpha": 64, "dora": True, "targets": ["c_attn", "c_proj", "c_fc"],
                   "keep_repetitions": 3, "wait_repetitions": 1, "repair_repetitions": 1, "loss": "per-example CE + EOS margin; no unrelated teacher",
                   "selection": "actual policy case F0.5, normal/pending/wrong-auto caps from previous model; validation only"}}
    (output / "run-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    bf16 = device == "cuda" and torch.cuda.is_bf16_supported()
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.float32, local_files_only=True).to(device)
    model = get_peft_model(model, LoraConfig(task_type=TaskType.CAUSAL_LM, r=32, lora_alpha=64,
        use_dora=True, lora_dropout=0., target_modules=["c_attn", "c_proj", "c_fc"], bias="none"))
    disable_all_dropout(model)
    model.print_trainable_parameters()
    def encode(rows, balanced=False):
        items = []
        for row in rows:
            encoded = encode_example(tokenizer, model_prompt(row["prompt"], row, args.input_contract), row["completion"], 384)
            items.extend([{**encoded, "preserve": row["expected_action"] in ("keep", "wait")}] * (3 if balanced and row["expected_action"] == "keep" else 1))
        return Dataset.from_list(items)
    base_collator = DataCollatorForSeq2Seq(tokenizer=tokenizer, pad_to_multiple_of=8)
    def collate(rows):
        batch = base_collator([{k:v for k,v in r.items() if k != "preserve"} for r in rows])
        batch["preserve"] = torch.tensor([r["preserve"] for r in rows], dtype=torch.bool)
        return batch
    trainer = QwertyTrainer(model=model, fixture=fixture, tokenizer_for_generation=tokenizer, clean_anchor=args.clean_anchor, input_contract=args.input_contract,
        baseline=baseline_report["summary"], args=TrainingArguments(output_dir=str(output),
            num_train_epochs=args.epochs, learning_rate=args.lr, per_device_train_batch_size=32,
            per_device_eval_batch_size=32, gradient_accumulation_steps=1, weight_decay=.01,
            warmup_steps=30, lr_scheduler_type="cosine", eval_strategy="epoch", save_strategy="epoch",
            save_total_limit=2, load_best_model_at_end=True, metric_for_best_model="eval_policy_score", greater_is_better=True, remove_unused_columns=False,
            seed=args.seed, data_seed=args.seed, bf16=bf16, fp16=device == "cuda" and not bf16,
            logging_steps=100, report_to="none", disable_tqdm=True),
        train_dataset=encode(train, True), eval_dataset=encode(val),
        data_collator=collate)
    trainer.train()
    trainer.save_model(str(output))
    tokenizer.save_pretrained(output)
    trainer.state.save_to_json(str(output / "trainer_state.json"))
    record_training_completed(output)
    print("Selected", trainer.state.best_model_checkpoint, trainer.state.best_metric, flush=True)


if __name__ == "__main__":
    main()
