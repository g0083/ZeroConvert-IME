"""Reading-candidate ranking contract shared by training and native inference."""
import math

import torch
import torch.nn.functional as F

from keyboard_hybrid import kata
from qwerty_local_eval import input_prompt


def encode_candidates(tokenizer, observation, candidates, max_length=256):
    # Only observable fields enter input_prompt. A common prefix is used even
    # when different candidates have different token lengths.
    answers = [tokenizer.encode(kata(c["reading"]), add_special_tokens=False)
               + [tokenizer.eos_token_id] for c in candidates]
    if not answers:
        return []
    prompt = input_prompt(observation)
    context, observed = prompt.split("\uee00", 1)
    fixed = tokenizer.encode("\uee00" + observed, add_special_tokens=False)
    room = max_length - len(fixed) - max(map(len, answers))
    if room < 0:
        raise ValueError("Complete observation and candidates exceed token budget")
    left = tokenizer.encode(context[1:], add_special_tokens=False) if context else []
    marker = tokenizer.encode("\uee02", add_special_tokens=False)
    prefix = (marker + left[-(room-len(marker)):] if left and room > len(marker) else []) + fixed
    return [{"input_ids": prefix + answer, "labels": [-100]*len(prefix)+answer,
             "prefix_length": len(prefix), "answer_tokens": len(answer)} for answer in answers]


def sequence_scores(logits, labels):
    labels = labels[:, 1:]
    valid = labels != -100
    ce = F.cross_entropy(logits[:, :-1].float().reshape(-1, logits.shape[-1]),
                         labels.reshape(-1), ignore_index=-100, reduction="none").reshape_as(labels)
    return -(ce*valid).sum(-1)/valid.sum(-1).clamp_min(1)


def ranking_loss(scores, width=4):
    grouped = scores.reshape(-1, width)
    # Positive is index zero in training tensors only. Runtime candidates keep
    # the unchanged observation first, and never see this target ordering.
    return F.cross_entropy(grouped*4., torch.zeros(len(grouped), dtype=torch.long, device=scores.device)) - .1*grouped[:, 0].mean()


def select(candidates, scores, margin):
    if not candidates or len(candidates) != len(scores):
        return None
    if not candidates[0]["unchanged"] or any(not math.isfinite(s) for s in scores):
        raise ValueError("Invalid ranked candidate set")
    best = max(range(len(scores)), key=lambda i: scores[i])
    return best if best and scores[best]-scores[0] > margin else None


def metrics(rows, records, margin):
    tp=wrong=keep=wait=total=coverage=0
    for row, record in zip(rows, records, strict=True):
        if row["id"] != record["id"]:
            raise ValueError("Case order mismatch")
        repair = row["expected_action"] == "repair"
        total += repair
        coverage += repair and any(c["reading"] == row["target_reading"] for c in record["candidates"])
        index = select(record["candidates"], record["scores"], margin)
        if index is not None:
            good = repair and record["candidates"][index]["reading"] == row["target_reading"]
            tp += good; wrong += not good
            keep += row["expected_action"] == "keep"
            wait += row["expected_action"] == "wait"
    return {"repair_correct":tp,"repair_total":total,"candidate_coverage":coverage,"wrong_proposed":wrong,
            "keep_proposed":keep,"keep_total":sum(r["expected_action"]=="keep" for r in rows),
            "wait_proposed":wait,"wait_total":sum(r["expected_action"]=="wait" for r in rows),
            "precision":tp/(tp+wrong) if tp+wrong else None,
            "f0_5":1.25*tp/(1.25*tp+wrong+.25*(total-tp)) if total+wrong else 0.}


def calibrate(rows, records):
    grid=[{"margin":m,"metrics":metrics(rows,records,m)} for m in (0.,.05,.1,.2,.4,.8,1.6,3.2,6.4,1000000.)]
    eligible=[r for r in grid if r["metrics"]["keep_proposed"]==0 and r["metrics"]["wait_proposed"]==0]
    return max(eligible,key=lambda r:r["metrics"]["f0_5"]),grid
