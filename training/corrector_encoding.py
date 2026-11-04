"""Keep the entire composing input and supervised answer inside the token budget."""


def encode_example(tokenizer, prompt, completion, max_length):
    if "\uee00" not in prompt or not prompt.endswith("\uee01"):
        raise ValueError("Invalid corrector prompt")
    context, target = prompt.split("\uee00", 1)
    target_tokens = tokenizer.encode("\uee00" + target, add_special_tokens=False)
    answer = tokenizer.encode(completion, add_special_tokens=False) + [tokenizer.eos_token_id]
    available = max_length - len(target_tokens) - len(answer)
    if available < 0:
        raise ValueError("Input and complete answer exceed max_length; increase the budget")
    prefix = []
    if context:
        if not context.startswith("\uee02"):
            raise ValueError("Invalid corrector context marker")
        marker = tokenizer.encode("\uee02", add_special_tokens=False)
        tokens = tokenizer.encode(context[1:], add_special_tokens=False)
        if available > len(marker):
            prefix = marker + tokens[-(available - len(marker)):]
    prompt_tokens = prefix + target_tokens
    return {"input_ids": prompt_tokens + answer, "labels": [-100] * len(prompt_tokens) + answer}
