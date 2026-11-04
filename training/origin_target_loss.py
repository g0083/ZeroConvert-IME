# SPDX-License-Identifier: Apache-2.0
"""Per-example Origin CE, projecting only supervised causal positions.

The decoder still processes every input token. Only zero-weight lm_head/CE
work is skipped; prompt tokens retain their indirect attention gradients.
"""


def target_balanced_ce(model, input_ids, attention_mask, labels):
    import torch
    import torch.nn.functional as F
    if type(model).__name__ != 'LlamaForCausalLM' or model.config.model_type != 'llama':
        raise ValueError('Target-only CE is verified for the owned plain Llama Origin model only')
    if input_ids.ndim != 2 or labels.shape != input_ids.shape or attention_mask.shape != input_ids.shape:
        raise ValueError('Origin input, mask and labels must have identical two-dimensional shapes')
    shifted = labels[:, 1:]
    supervised = shifted.ne(-100)
    counts = supervised.sum(1)
    if (counts == 0).any():
        raise ValueError('An example has no supervised target')
    hidden = model.model(input_ids=input_ids, attention_mask=attention_mask, use_cache=False,
                         return_dict=True).last_hidden_state
    # EOS is a regular supervised label here. Last input hidden state has no
    # next-token label, exactly as in balanced_ce's causal shift.
    logits = model.lm_head(hidden[:, :-1][supervised])
    losses = F.cross_entropy(logits.float(), shifted[supervised], reduction='none')
    per_position = torch.zeros_like(shifted, dtype=losses.dtype).masked_scatter(supervised, losses)
    return (per_position.sum(1) / counts).mean()
