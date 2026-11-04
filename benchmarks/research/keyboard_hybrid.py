"""Offline keyboard-channel + dictionary candidate experiment (not the IME).

Candidates depend only on observed keys and the installed AZR2 dictionary.
Gold readings are used by the evaluator, never by this module. The simplified
romaji decoder uses the pinned converter's table; it is not a TSF integration.
"""
import functools
import math
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / 'windows/server-swift/.build/checkouts/AzooKeyKanaKanjiConverter/Sources/KanaKanjiConverterModule/InputManagement/InputTables/defaultRoman2Kana.swift'
DICTIONARY = ROOT / 'ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin'


def hira(text):
    return ''.join(chr(ord(c) - 96) if 'ァ' <= c <= 'ヶ' else c for c in text)


def kata(text):
    return ''.join(chr(ord(c) + 96) if 'ぁ' <= c <= 'ゖ' else c for c in text)


def is_kana(text):
    return bool(text) and all('ぁ' <= c <= 'ゖ' or c == 'ー' for c in text)


class RomanDecoder:
    def __init__(self, path=TABLE):
        pairs = re.findall(r'^\s*"([a-z]+)": "([^"]+)"', path.read_text(encoding='utf-8'), re.M)
        self.mapping = dict(pairs)
        if len(self.mapping) < 200:
            raise ValueError('Unexpected romaji table')

    @functools.lru_cache(maxsize=100000)
    def decode(self, keys):
        output, i = [], 0
        while i < len(keys):
            # Doubled consonants leave the second key pending (nn is special).
            if i + 1 < len(keys) and keys[i] == keys[i+1] and keys[i] not in 'aeioun':
                output.append('っ')
                i += 1
                continue
            found = False
            for width in range(min(4, len(keys)-i), 0, -1):
                part = keys[i:i+width]
                value = self.mapping.get(part)
                if value and is_kana(value):
                    output.append(value)
                    i += width
                    found = True
                    break
            if found:
                continue
            if keys[i] == 'n' and (i+1 == len(keys) or keys[i+1] not in 'aiueoy'):
                output.append('ん')
            elif keys[i] == '-':
                output.append('ー')
            else:
                output.append(keys[i])
            i += 1
        return ''.join(output)


def keyboard_neighbors():
    positions = {c: (x+offset, y) for y, (row, offset) in enumerate(
        [('qwertyuiop', 0), ('asdfghjkl', .25), ('zxcvbnm', .75)]) for x, c in enumerate(row)}
    return {c: ''.join(d for d, q in positions.items() if c != d and math.dist(p, q) <= 1.26)
            for c, p in positions.items()}


NEIGHBORS = keyboard_neighbors()


def key_candidates(observed):
    """One adjacent substitution, including both a/s directions. Hard length cap."""
    if not 1 <= len(observed) <= 64 or not re.fullmatch('[a-z]+', observed):
        raise ValueError('Expected 1..64 lowercase romaji keys')
    yield observed, None
    for i, old in enumerate(observed):
        for new in NEIGHBORS[old]:
            yield observed[:i] + new + observed[i+1:], (i, old, new)


class Lexicon:
    def __init__(self, path=DICTIONARY):
        data = path.read_bytes()
        if data[:4] != b'AZR2':
            raise ValueError('Expected AZR2 dictionary')
        count = struct.unpack_from('<I', data, 4)[0]
        offsets = struct.unpack_from(f'<{count+1}I', data, 8)
        entries = {}
        for start, end in zip(offsets, offsets[1:]):
            sl, rl, score = struct.unpack_from('<HHf', data, start)
            if start + 8 + sl + rl != end or not math.isfinite(score):
                raise ValueError('Invalid AZR2 entry')
            surface = data[start+8:start+8+sl].decode('utf-8')
            reading = hira(data[start+8+sl:end].decode('utf-8'))
            if not is_kana(reading) or not 1 <= len(reading) <= 40 or not surface.strip():
                continue
            options = entries.setdefault(reading, {})
            options[surface] = max(score, options.get(surface, -math.inf))
        self.entries = {r: sorted(((v, s) for s, v in options.items()), reverse=True)[:3]
                        for r, options in entries.items()}

    @functools.lru_cache(maxsize=100000)
    def convert(self, reading, beam=3):
        """Bounded unigram lattice. Unknown kana survives with an explicit cost."""
        paths = [[] for _ in range(len(reading)+1)]
        paths[0] = [(0., '', 0)]
        for start in range(len(reading)):
            current = sorted(paths[start], reverse=True)
            seen = set()
            selected = []
            for item in current:
                if item[1] not in seen:
                    selected.append(item)
                    seen.add(item[1])
                if len(selected) == beam:
                    break
            for end in range(start+1, min(len(reading), start+40)+1):
                piece = reading[start:end]
                options = self.entries.get(piece, [])
                unknown = False
                if end == start+1 and not options:
                    options = [(-24. if is_kana(piece) else -40., piece)]
                    unknown = True
                for score, surface in options:
                    for previous, text, missing in selected:
                        paths[end].append((previous+score, text+surface, missing+int(unknown)))
        seen = set()
        result = []
        for score, text, missing in sorted(paths[-1], reverse=True):
            if text not in seen:
                result.append((score, text, missing))
                seen.add(text)
            if len(result) == beam:
                break
        return tuple(result)


def generate(observed, decoder, lexicon, reading_limit=24, surface_beam=3):
    grouped = {}
    original = decoder.decode(observed)
    for keys, edit in key_candidates(observed):
        reading = decoder.decode(keys)
        # Invalid intermediate romaji can still be the unmodified baseline.
        if reading != original and not is_kana(reading):
            continue
        if reading not in grouped:
            paths = lexicon.convert(reading, surface_beam)
            grouped[reading] = {'reading': reading, 'keys': keys, 'edit': edit,
                                'paths': paths, 'dictionary': paths[0][0]}
    ordered = sorted(grouped.values(), key=lambda c: (-c['dictionary'], c['reading']))
    retained = ordered[:reading_limit]
    if not any(c['reading'] == original for c in retained):
        retained = retained[:reading_limit-1] + [grouped[original]]
    candidates = []
    for item in retained:
        for score, surface, unknown in item['paths']:
            candidates.append({k: item[k] for k in ('reading', 'keys', 'edit')} |
                              {'surface': surface, 'dictionary': score, 'unknown': unknown})
    return candidates, [c['reading'] for c in ordered]


class NeuralScorer:
    """Full answer likelihood, same observed-input prompt for every candidate.

    Research HF execution; GPU timing is not Windows CPU deployment latency.
    No gold is ever encoded. Padding and prompt positions are excluded.
    """
    def __init__(self, model_path, device='cuda', batch_size=48):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        self.torch = torch
        self.tokenizer = AutoTokenizer.from_pretrained(model_path, local_files_only=True)
        self.model = AutoModelForCausalLM.from_pretrained(
            model_path, local_files_only=True, dtype=torch.float32).to(device).eval()
        self.device, self.batch_size = device, batch_size

    def score(self, observed_reading, candidates, mode='conditional'):
        from corrector_encoding import encode_example
        torch = self.torch
        prompt = '\uee00' + kata(observed_reading) + '\uee01'
        if mode == 'conditional':
            identities = [c['surface'] for c in candidates]
            unique = list(dict.fromkeys(identities))
            encoded = [encode_example(self.tokenizer, prompt, s, 256) for s in unique]
        elif mode == 'reading-prior':
            # The upstream experimental noisy-channel API uses this exact
            # prefix. Score a complete reading including its boundary token.
            identities = [c['reading'] for c in candidates]
            unique = list(dict.fromkeys(identities))
            prefix = self.tokenizer.encode('\uee00', add_special_tokens=False)
            encoded = []
            for reading in unique:
                answer = self.tokenizer.encode(kata(reading)+'\uee01', add_special_tokens=False)
                encoded.append({'input_ids': prefix+answer, 'labels': [-100]*len(prefix)+answer})
        else:
            raise ValueError('Unknown scoring mode')
        scores = []
        with torch.inference_mode():
            for start in range(0, len(encoded), self.batch_size):
                rows = encoded[start:start+self.batch_size]
                width = max(len(r['input_ids']) for r in rows)
                ids = torch.full((len(rows), width), self.tokenizer.pad_token_id or 1,
                                 dtype=torch.long, device=self.device)
                labels = torch.full_like(ids, -100)
                mask = torch.zeros_like(ids)
                for i, row in enumerate(rows):
                    n = len(row['input_ids'])
                    ids[i, :n] = torch.tensor(row['input_ids'], device=self.device)
                    labels[i, :n] = torch.tensor(row['labels'], device=self.device)
                    mask[i, :n] = 1
                logits = self.model(input_ids=ids, attention_mask=mask).logits[:, :-1, :].float()
                targets = labels[:, 1:]
                losses = torch.nn.functional.cross_entropy(
                    logits.reshape(-1, logits.shape[-1]), targets.reshape(-1),
                    ignore_index=-100, reduction='none').reshape(targets.shape)
                for total, n in zip(losses.sum(1).tolist(), (targets != -100).sum(1).tolist()):
                    scores.append({'lm_sum': -total, 'lm_mean': -total/n, 'answer_tokens': n})
        lookup = dict(zip(unique, scores))
        return [lookup[key] for key in identities]
