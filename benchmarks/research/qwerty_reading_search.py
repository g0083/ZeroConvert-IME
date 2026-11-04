"""Bounded one-key reading search with native verification and no gold access."""
from functools import lru_cache
import math

from keyboard_hybrid import DICTIONARY, Lexicon, RomanDecoder, is_kana
from native_qwerty import NativeQwerty
from qwerty_channel_filter import edits


class ReadingSearch:
    def __init__(self, limit=24):
        if not 2 <= limit <= 32:
            raise ValueError("Expected 2..32 readings")
        self.limit = limit
        self.native = NativeQwerty()
        self.decoder = RomanDecoder()
        lexicon = Lexicon(DICTIONARY)
        self.trie = {}
        for reading, options in lexicon.entries.items():
            node = self.trie
            for char in reading:
                node = node.setdefault(char, {})
            node[None] = max(score for score, _ in options)

    @lru_cache(maxsize=100000)
    def dictionary_score(self, reading):
        scores = [-math.inf] * (len(reading)+1)
        scores[0] = 0.
        for start in range(len(reading)):
            scores[start+1] = max(scores[start+1], scores[start] - 24.)
            node = self.trie
            for end in range(start, min(start+40, len(reading))):
                node = node.get(reading[end])
                if node is None:
                    break
                if None in node:
                    scores[end+1] = max(scores[end+1], scores[start]+node[None])
        return scores[-1]

    def generate(self, observation):
        keys = observation.get("observed_keys")
        if not keys or len(keys)>96 or not all(c in "abcdefghijklmnopqrstuvwxyz" for c in keys):
            return []
        original = self.native.decode(keys)
        baseline = {"keys":keys, "reading":original, "dictionary":self.dictionary_score(original), "unchanged":True}
        # Input state is observable; no expected_action/typo_type/target is used.
        if observation.get("input_state") == "backspace" or (original and original[-1].isascii() and original[-1].isalpha()):
            return [baseline]
        grouped = {}
        for candidate in edits(keys):
            approximate = self.decoder.decode(candidate)
            if is_kana(approximate) and approximate != original:
                grouped.setdefault(approximate, candidate)
        ordered = sorted(grouped, key=lambda r:(-self.dictionary_score(r), r))
        retained = [baseline]
        seen = {original}
        for reading in ordered:
            candidate = grouped[reading]
            actual = self.native.decode(candidate)
            if not is_kana(actual) or actual in seen:
                continue
            seen.add(actual)
            retained.append({"keys":candidate, "reading":actual, "dictionary":self.dictionary_score(actual), "unchanged":False})
            if len(retained) == self.limit:
                break
        return retained


def choose(candidates, weight, margin):
    if not candidates:
        return None
    baseline = candidates[0]
    def score(c):
        return c["dictionary"] + weight*c.get("lm_sum",0.)
    alternatives = [c for c in candidates if not c["unchanged"]]
    if not alternatives:
        return None
    best = max(alternatives, key=lambda c:score(c))
    return best if score(best)-score(baseline) > margin else None
