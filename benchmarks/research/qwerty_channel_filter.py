"""Bounded research candidate veto: original input competes with a proposal.

Approximate decoder prefilters edits; every accepted path is verified natively.
This is a conservative filter, not a trained reranker or an IME hot path.
"""
from copy import deepcopy

from evaluate_v4_repair_comparison import ReadingVerifier
from keyboard_hybrid import NEIGHBORS, RomanDecoder
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import DICTIONARY, summary


def edits(keys):
    result = set()
    for i, old in enumerate(keys):
        result.add(keys[:i]+keys[i+1:])
        for new in NEIGHBORS.get(old, ""):
            result.add(keys[:i]+new+keys[i+1:])
        if i+1 < len(keys):
            result.add(keys[:i]+keys[i+1]+old+keys[i+2:])
    for i in range(len(keys)+1):
        for new in "abcdefghijklmnopqrstuvwxyz":
            result.add(keys[:i]+new+keys[i:])
    return sorted(result - {"", keys})


class ChannelFilter:
    def __init__(self):
        self.native = NativeQwerty()
        self.decoder = RomanDecoder()
        self.verifier = ReadingVerifier(DICTIONARY)

    def witness(self, observation, surface):
        keys = observation.get("observed_keys")
        if not keys or len(keys) > 96 or not all(c in "abcdefghijklmnopqrstuvwxyz" for c in keys):
            return None
        for candidate in edits(keys):
            approximate = self.decoder.decode(candidate)
            if self.verifier.matches(surface, approximate):
                actual = self.native.decode(candidate)
                if self.verifier.matches(surface, actual):
                    return {"keys": candidate, "reading": actual}
        return None

    def apply(self, result, rows, channel=True):
        output = deepcopy(result)
        lookup = {r["id"]: r for r in rows}
        for item in output["report"]["cases"]:
            row = lookup[item["id"]]
            proposed = item["correction"]
            reason = None
            if proposed:
                if row.get("input_state") == "backspace":
                    reason = "backspace-state"
                elif channel and row.get("observed_keys") is not None:
                    witness = self.witness(row, proposed)
                    item["channel_witness"] = witness
                    if not witness:
                        reason = "no-verified-one-key-path"
            if reason:
                item["veto_reason"] = reason
                item["correction"] = None
                item["auto_apply"] = False
        output["summary"] = summary(output["report"], rows)
        output["filter"] = "state-and-verified-key-channel" if channel else "state-only"
        output["timing_scope"] = "Offline candidate filtering; no IME or model latency claim"
        return output
