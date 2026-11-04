# SPDX-License-Identifier: Apache-2.0
"""Bounded key edits and finite repair choices; no reference-dependent generation."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

from keyboard_hybrid import Lexicon, RomanDecoder, key_candidates, is_kana
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def edits(raw, extended=True):
    if not re.fullmatch('[a-z]{1,64}', raw):
        raise ValueError('Supported candidate input: 1..64 lowercase keys')
    seen = set()
    for keys, _ in key_candidates(raw):
        if keys not in seen:
            seen.add(keys)
            yield keys
    if not extended:
        return
    for i in range(len(raw)):
        values = [raw[:i] + raw[i+1:]]
        if i+1 < len(raw):
            values.append(raw[:i] + raw[i+1] + raw[i] + raw[i+2:])
        for keys in values:
            if keys and keys not in seen:
                seen.add(keys)
                yield keys
    for i in range(len(raw)+1):
        for vowel in 'aeiou':
            keys = raw[:i] + vowel + raw[i:]
            if keys not in seen:
                seen.add(keys)
                yield keys


def candidates(raw, decoder, lexicon, limit=8, extended=True):
    if not 2 <= limit <= 16:
        raise ValueError('Candidate limit must be 2..16')
    observed = decoder.decode(raw)
    choices = {}
    for keys in edits(raw, extended):
        reading = decoder.decode(keys)
        if reading != observed and not is_kana(reading):
            continue
        if reading in choices:
            continue
        paths = lexicon.convert(reading, 1)
        choices[reading] = {'reading': reading, 'keys': keys, 'dictionary': paths[0][0]}
    ranked = sorted(choices.values(), key=lambda c: (-c['dictionary'], c['reading']))
    retained = ranked[:limit]
    if observed not in {c['reading'] for c in retained}:
        retained = retained[:limit-1] + [choices[observed]]
    return retained, set(choices)


class TokenTrie:
    def __init__(self, choices):
        self.root = {}
        for choice in choices:
            node = self.root
            for token in choice:
                node = node.setdefault(token, {})

    def allowed(self, prefix):
        node = self.root
        for token in prefix:
            if token not in node:
                raise ValueError('Generated tokens escaped candidate trie')
            node = node[token]
        return sorted(node) if node else [3]


def repair_choices(case, codec):
    # No gold/reference fields are consulted here.
    result = [[codec.ids['<keep>'], 3], [codec.ids['<abstain>'], 3]]
    for c in case['candidates']:
        if c['reading'] == case['observed'] or not is_kana(c['reading']):
            continue
        result.append([codec.ids['<emit>']] + codec.text(c['reading']) + [3])
    return result


def constrained_generate(model, prompts, choices, device, max_tokens=96):
    import torch
    width = max(map(len, prompts))
    if width + max_tokens > model.config.n_positions:
        raise ValueError('Generation exceeds position limit')
    ids = torch.full((len(prompts), width), 1, dtype=torch.long, device=device)
    mask = torch.zeros_like(ids)
    for index, prompt in enumerate(prompts):
        ids[index, -len(prompt):] = torch.tensor(prompt, device=device)
        mask[index, -len(prompt):] = 1
    tries = [TokenTrie(c) for c in choices]
    def allowed(batch_id, tokens):
        prefix = tokens[width:].tolist()
        return [1] if 3 in prefix else tries[batch_id].allowed(prefix)
    with torch.no_grad():
        generated = model.generate(input_ids=ids, attention_mask=mask, max_new_tokens=max_tokens,
                                   do_sample=False, pad_token_id=1, eos_token_id=3,
                                   prefix_allowed_tokens_fn=allowed, use_cache=True)
    outputs = []
    for value in generated[:, width:].tolist():
        outputs.append(value[:value.index(3)+1] if 3 in value else value)
    return outputs


def build(fixture, observation_codec, out, limit=8, extended=True):
    if out.exists():
        raise FileExistsError(out)
    codec = V4UnifiedCodec(observation_codec)
    table = observation_codec / 'qwerty-observation-table.json'
    decoder, lexicon = RomanDecoder(table), Lexicon()
    original = json.loads(fixture.read_text(encoding='utf-8'))
    cases, summary = [], Counter()
    for row in original['cases']:
        if row['input']['pending']:
            continue
        raw = row['input']['raw']
        try:
            selected, all_readings = candidates(raw, decoder, lexicon, limit, extended)
        except ValueError:
            cases.append({**row, 'candidates': [], 'supported': False})
            summary['unsupported'] += 1
            continue
        if decoder.decode(raw) != row['observed'] or codec.observed_reading(raw, '') != row['observed']:
            raise ValueError('Candidate and fixture observation decoders differ')
        cases.append({**row, 'candidates': selected, 'supported': True})
        if row['target']['action'] == 'emit':
            summary['typo_total'] += 1
            summary['all_edit_coverage'] += int(row['reference_reading'] in all_readings)
            summary['retained_coverage'] += int(row['reference_reading'] in {c['reading'] for c in selected})
        else:
            summary['normal_total'] += 1
    write_json(out, {'schema': 'v4-repair-candidates-v1', 'cases': cases, 'summary': dict(summary),
                     'fixture_sha256': digest(fixture), 'limit': limit, 'extended': extended,
                     'table_sha256': digest(table), 'dictionary_sha256': digest(Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin')),
                     'code_sha256': digest(__file__), 'generation_uses_gold': False,
                     'limits': original['limits'] + ['One edit operation, bounded 64-key input, dictionary-only shortlist']})
    print(json.dumps(dict(summary)), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fixture', type=Path, required=True)
    p.add_argument('--observation-codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--limit', type=int, default=8)
    p.add_argument('--adjacent-only', action='store_true')
    a = p.parse_args()
    build(a.fixture, a.observation_codec, a.out, a.limit, not a.adjacent_only)
