"""Train-only edit emphasis and same-prefix contrast against an identity patch."""
from difflib import SequenceMatcher
import torch
import torch.nn.functional as F
from corrector_encoding import encode_example


def encode_repair(tokenizer,prompt,target,max_length,repair_weight):
    result=encode_example(tokenizer,prompt,target,max_length)
    answer=tokenizer.encode(target,add_special_tokens=False)+[tokenizer.eos_token_id]
    start=len(result["input_ids"])-len(answer)
    weights=[0.]*start+[1.]*len(answer)
    anti=[-100]*len(result["input_ids"])
    if target.startswith("E:"):
        _,old,new=target.split(":")
        if old==new: raise ValueError("Identity must not be a repair teacher")
        identity=tokenizer.encode(f"E:{old}:{old}",add_special_tokens=False)+[tokenizer.eos_token_id]
        # The contrast is only at the first different token: its causal prefix
        # is identical for the correct and identity continuations.
        first=next(i for i,(good,bad) in enumerate(zip(answer,identity)) if good!=bad)
        anti[start+first]=identity[first]
        # Alignment is a BPE-level supervision proxy, not linguistic edits.
        for op,a,b,_,_ in SequenceMatcher(a=answer,b=identity,autojunk=False).get_opcodes():
            if op!="equal":
                for i in range(a,b) if a<b else [min(a,len(answer)-1)]: weights[start+i]=repair_weight
    result.update(token_weights=weights,anti_labels=anti)
    return result


def repair_loss(logits,labels,weights,anti,contrast_weight=0.,margin=2.):
    logits=logits[:,:-1].float(); labels=labels[:,1:]
    weights=weights[:,1:]; anti=anti[:,1:]
    valid=labels!=-100
    ce=F.cross_entropy(logits.reshape(-1,logits.shape[-1]),labels.reshape(-1),ignore_index=-100,reduction="none").reshape_as(labels)
    effective=weights*valid
    loss=((ce*effective).sum(-1)/effective.sum(-1).clamp_min(1.)).mean()
    contrast=(anti!=-100)&valid&(anti!=labels)
    if contrast_weight and contrast.any():
        selected=logits[contrast]
        good=selected.gather(-1,labels[contrast].unsqueeze(-1)).squeeze(-1)
        bad=selected.gather(-1,anti[contrast].unsqueeze(-1)).squeeze(-1)
        penalties=torch.zeros_like(labels,dtype=logits.dtype)
        penalties[contrast]=F.relu(margin+bad-good)
        loss=loss+contrast_weight*(penalties.sum(-1)/contrast.sum(-1).clamp_min(1)).mean()
    eos=labels==3
    if eos.any():
        values=logits[eos]; wrong=[i for i in (172,248,4,469) if i<values.shape[-1]]
        if wrong: loss=loss+.05*F.relu(6.-values[:,3]+values[:,wrong].max(-1).values).mean()
    return loss
