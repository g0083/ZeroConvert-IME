"""Research-only observable QWERTY metadata. Never reads correction labels."""
import json


def model_prompt(prompt, observation, contract="kana"):
    if contract == "kana":
        return prompt
    if contract != "raw-state":
        raise ValueError(contract)
    keys = observation.get("observed_keys")
    state = observation.get("input_state", "unknown")
    cursor = observation.get("cursor_key_offset")
    if keys is not None and (not isinstance(keys, str) or not keys.isascii() or len(keys) > 128):
        raise ValueError("Invalid observed keys")
    if state not in ("append", "backspace", "unknown"):
        raise ValueError("Invalid input state")
    if cursor is not None and (keys is None or not isinstance(cursor, int) or not 0 <= cursor <= len(keys)):
        raise ValueError("Invalid cursor")
    prefix, reading = prompt.split("\uee00", 1)
    metadata = json.dumps({"keys": keys, "state": state, "cursor": cursor}, ensure_ascii=True, separators=(",", ":"))
    return prefix + "\uee00[QWERTY " + metadata + "]" + reading
