# SPDX-License-Identifier: Apache-2.0
"""Build a source-separated multitask Origin pilot from pinned Magpie text.

UniDic's orthographic kana (not pronunciation) preserves particles and vowel
spelling. Automatically labeled pilot, not a human-certified production corpus.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import heapq
import importlib.metadata
import json
from pathlib import Path
import random
import re
import shutil
import unicodedata

from dataset_isolation import EVALUATION_FILES, evaluation_keys, normalize
from origin_contract import digest, text_key, validate_row, write_json

SOURCE_ID = 'magpie-sft-general-ja-60k'
KANJI = re.compile(r'[一-鿿々]')
KANA = re.compile(r'^[ぁ-ゖァ-ヶーゝゞヽヾ]+$')
HIRAGANA = re.compile(r'^[ぁ-ゖーゝゞ]+$')
ROMAJI = {}
for kana, syllables in [
    ('あいうえお', 'a i u e o'), ('かきくけこ', 'ka ki ku ke ko'),
    ('さしすせそ', 'sa shi su se so'), ('たちつてと', 'ta chi tsu te to'),
    ('なにぬねの', 'na ni nu ne no'), ('はひふへほ', 'ha hi fu he ho'),
    ('まみむめも', 'ma mi mu me mo'), ('やゆよ', 'ya yu yo'),
    ('らりるれろ', 'ra ri ru re ro'), ('わをん', 'wa wo nn'),
    ('がぎぐげご', 'ga gi gu ge go'), ('ざじずぜぞ', 'za ji zu ze zo'),
    ('だぢづでど', 'da di du de do'), ('ばびぶべぼ', 'ba bi bu be bo'),
    ('ぱぴぷぺぽ', 'pa pi pu pe po'), ('ぁぃぅぇぉゃゅょゎ', 'xa xi xu xe xo xya xyu xyo xwa'),
    ('ゔー', 'vu -'),
]:
    ROMAJI.update(zip(kana, syllables.split()))
for first, stem in [('き', 'ky'), ('し', 'sh'), ('ち', 'ch'), ('に', 'ny'), ('ひ', 'hy'),
                    ('み', 'my'), ('り', 'ry'), ('ぎ', 'gy'), ('じ', 'j'), ('び', 'by'), ('ぴ', 'py')]:
    for last, vowel in [('ゃ', 'a'), ('ゅ', 'u'), ('ょ', 'o')]:
        ROMAJI[first + last] = stem + vowel


def hira(text):
    return ''.join(chr(ord(c) - 96) if 'ァ' <= c <= 'ヶ' else c for c in text)


def romanize(text):
    result, i = [], 0
    while i < len(text):
        if text[i] == 'っ':
            following = next((ROMAJI[text[i + 1:i + 1 + n]] for n in (2, 1)
                              if text[i + 1:i + 1 + n] in ROMAJI), '')
            if following and following[0] in 'bcdfghjklmpqrstvwxyz':
                result.append(following[0])
            else:
                result.append('xtsu')
            i += 1
            continue
        pair = text[i:i + 2]
        key = pair if pair in ROMAJI else text[i]
        if key not in ROMAJI:
            return None
        result.append(ROMAJI[key])
        i += len(key)
    return ''.join(result)


def orthographic_tokens(text, tagger):
    tokens, offset = [], 0
    for word in tagger(text):
        start = text.find(word.surface, offset)
        if start < 0 or text[offset:start].strip():
            raise ValueError('Unaligned token')
        if start > offset:
            tokens.append((text[offset:start], text[offset:start]))
        surface = word.surface
        if KANA.fullmatch(surface):
            reading = hira(surface)
        elif KANJI.search(surface):
            reading = hira(word.feature.kana or '')
            if not HIRAGANA.fullmatch(reading):
                raise ValueError('Unknown kanji reading')
        else:
            reading = surface
        tokens.append((surface, reading))
        offset = start + len(surface)
    if offset < len(text):
        tokens.append((text[offset:], text[offset:]))
    if ''.join(s for s, _ in tokens) != text:
        raise ValueError('Tokenizer changed surface')
    return tokens


def noisy_raw(raw, rng):
    if len(raw) < 3:
        return None, None
    i = rng.randrange(1, len(raw) - 1)
    kind = rng.choice(['transpose', 'drop', 'duplicate', 'neighbor'])
    if kind == 'transpose':
        value = raw[:i] + raw[i + 1] + raw[i] + raw[i + 2:]
    elif kind == 'drop':
        value = raw[:i] + raw[i + 1:]
    elif kind == 'duplicate':
        value = raw[:i] + raw[i] + raw[i:]
    else:
        keyboard = {'a': 'sq', 'i': 'uo', 'u': 'yi', 'e': 'wr', 'o': 'ip', 'k': 'jl',
                    'n': 'bm', 's': 'ad', 't': 'ry', 'h': 'gj', 'm': 'nj'}
        neighbors = keyboard.get(raw[i])
        if not neighbors:
            return None, None
        value = raw[:i] + rng.choice(neighbors) + raw[i + 1:]
    return (value, kind) if value != raw else (None, None)


def expand(sentence, context, tokens, doc_id, sentence_index, provenance, rng):
    common = {'source_id': SOURCE_ID, 'document_id': doc_id, 'source_text': sentence,
              'provenance': provenance, 'reading_quality': 'unidic-orthographic-auto-unreviewed'}
    variants = []
    repair_reference = None
    reading = ''.join(r for _, r in tokens)
    variants.append(('convert', {'ctx': context[-48:], 'reading': reading}, 'emit', sentence, 'full'))
    # Source-aligned partial conversion, distinct from prediction completion.
    if len(tokens) >= 5:
        end = rng.randrange(3, len(tokens))
        short = tokens[:end]
        surface, kana = ''.join(s for s, _ in short), ''.join(r for _, r in short)
        if len(surface) >= 4:
            variants.append(('convert', {'ctx': '', 'reading': kana}, 'emit', surface, 'prefix'))
    # Repair one bounded kana phrase; symbols/numbers are not silently removed.
    chunks = []
    current = ''
    for _, value in tokens:
        if HIRAGANA.fullmatch(value):
            current += value
        elif current:
            chunks.append(current)
            current = ''
    if current:
        chunks.append(current)
    chunks = [s for s in chunks if 4 <= len(s) <= 36]
    if chunks:
        target = rng.choice(chunks)
        repair_reference = target
        raw = romanize(target)
        if raw:
            # Identical observed-field schema for every repair label. Supplying
            # gold kana only for clean examples made slot presence reveal keep.
            inputs = {'ctx': context[-32:], 'raw': raw, 'pending': ''}
            variants.append(('repair_qwerty', inputs, 'keep', '', 'clean'))
            # Second normal example without context for the 60-80% normal mixture.
            variants.append(('repair_qwerty', {**inputs, 'ctx': ''}, 'keep', '', 'clean-no-context'))
            noise, kind = noisy_raw(raw, rng)
            if noise:
                # No claim that surface is the real IME rendering of a typo.
                variants.append(('repair_qwerty', {'ctx': context[-32:], 'raw': noise, 'pending': ''},
                                 'emit', target, 'synthetic-' + kind))
            variants.append(('repair_qwerty', {'ctx': '', 'raw': raw + 'k', 'pending': 'k'}, 'abstain', '', 'pending'))
    if len(tokens) >= 4:
        boundary = max(1, len(tokens) - rng.randrange(1, min(5, len(tokens))))
        prefix = ''.join(s for s, _ in tokens[:boundary])
        suffix = ''.join(s for s, _ in tokens[boundary:])
        if 1 <= len(suffix) <= 16 and prefix:
            variants.append(('predict', {'ctx': context[-48:], 'surface': prefix}, 'emit', suffix, 'suffix'))
    variants.append(('predict', {'ctx': context[-48:], 'surface': sentence}, 'abstain', '', 'complete'))
    for i, (task, inputs, action, target, category) in enumerate(variants):
        row = {**common, 'id': f'{doc_id}-s{sentence_index}-{i}', 'task': task,
               'input': inputs, 'target': {'action': action, 'text': target}, 'category': category}
        if task == 'repair_qwerty':
            row['reference_reading'] = repair_reference  # Evaluation only; never encoded as model input.
        validate_row(row)
        yield row


class NearDocuments:
    """Bottom-8 character shingles retrieve candidates; full Jaccard confirms.

    Approximate retrieval is explicitly not exhaustive semantic decontamination.
    """
    def __init__(self):
        self.index = defaultdict(list)
        self.texts = []

    @staticmethod
    def shingles(text):
        return {text[i:i + 5] for i in range(max(1, len(text) - 4))}

    def duplicate(self, text, remember=True):
        text = text_key(text)[:4096]
        shingles = self.shingles(text)
        keys = heapq.nsmallest(8, {hashlib.blake2b(s.encode(), digest_size=8).digest() for s in shingles})
        candidates = set()
        for key in keys:
            candidates.update(self.index[key][-200:])
        for candidate in candidates:
            other = self.texts[candidate]
            if min(len(text), len(other)) / max(len(text), len(other), 1) < .8:
                continue
            previous = self.shingles(other)
            if len(shingles & previous) / max(1, len(shingles | previous)) >= .85:
                return True
        if not remember:
            return False
        index = len(self.texts)
        self.texts.append(text)
        for key in keys:
            self.index[key].append(index)
        return False


def retain_labeler_licenses(evidence):
    for name in ('fugashi', 'unidic-lite'):
        dist = importlib.metadata.distribution(name)
        for file in dist.files or []:
            if Path(str(file)).name.lower() in ('license', 'license.txt', 'notice', 'notice.txt'):
                source = Path(dist.locate_file(file))
                target = evidence / f'{name}-{source.name}'
                if source.is_file() and not target.exists():
                    shutil.copy2(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--sealed-out', type=Path, required=True)
    parser.add_argument('--max-sentences', type=int, default=100000)
    parser.add_argument('--seed', type=int, default=20261029)
    args = parser.parse_args()
    if args.out.exists() or args.sealed_out.exists():
        raise FileExistsError('Use fresh output directories')
    acquisition = json.loads((args.source / 'acquisition-manifest.json').read_text(encoding='utf-8'))
    for name, info in acquisition['files'].items():
        if digest(args.source / name) != info['sha256']:
            raise ValueError(f'Acquired source changed: {name}')
    import fugashi
    import unidic_lite
    tagger = fugashi.Tagger()
    args.out.mkdir(parents=True)
    args.sealed_out.mkdir(parents=True)
    counts = Counter()
    reserved = evaluation_keys()
    near = NearDocuments()
    near_sealed = NearDocuments()
    sentences_seen, examples_seen = set(), set()
    handles = {name: (args.out / f'{name}.jsonl').open('x', encoding='utf-8') for name in ('train', 'val')}
    sealed = (args.sealed_out / 'documents.jsonl').open('x', encoding='utf-8')
    rejected = (args.out / 'reading-review-queue.jsonl').open('x', encoding='utf-8')
    try:
        # Reserve every held-out document *before* generating any task example.
        # This also excludes exact held-out sentences appearing in other docs.
        for path in sorted((args.source / 'data').glob('train-*.jsonl')):
            with path.open(encoding='utf-8') as stream:
                for line in stream:
                    original = json.loads(line)
                    doc_id = f'magpie-{original["provenance"]["source_record_id"]}'
                    bucket = int(hashlib.sha256(f'{args.seed}:{doc_id}'.encode()).hexdigest()[:8], 16) % 100
                    if bucket >= 10:
                        continue
                    sealed.write(json.dumps(original, ensure_ascii=False) + '\n')
                    counts['sealed_documents'] += 1
                    near_sealed.duplicate('\n'.join(m['content'] for m in original['messages']))
                    for message in original['messages']:
                        for match in re.finditer(r'[^。！？\n]+[。！？]?', message['content']):
                            sentence = match.group().strip()
                            if 8 <= len(sentence) <= 96:
                                reserved.add(normalize(sentence))
        print(f'Withheld {counts["sealed_documents"]} source documents before task expansion', flush=True)
        for path in sorted((args.source / 'data').glob('train-*.jsonl')):
            with path.open(encoding='utf-8') as stream:
                for line in stream:
                    original = json.loads(line)
                    provenance = original['provenance']
                    doc_id = f'magpie-{provenance["source_record_id"]}'
                    messages = original['messages']
                    doc_text = '\n'.join(m['content'] for m in messages)
                    bucket = int(hashlib.sha256(f'{args.seed}:{doc_id}'.encode()).hexdigest()[:8], 16) % 100
                    if bucket < 10:
                        continue
                    if near_sealed.duplicate(doc_text, remember=False):
                        counts['near_withheld_documents'] += 1
                        continue
                    if near.duplicate(doc_text):
                        counts['near_duplicate_documents'] += 1
                        continue
                    partition = 'val' if bucket < 15 else 'train'
                    counts[partition + '_documents'] += 1
                    rng = random.Random(f'{args.seed}:{doc_id}')
                    accepted = 0
                    sentence_index = 0
                    for message in messages:
                        context = ''
                        for match in re.finditer(r'[^。！？\n]+[。！？]?', message['content']):
                            sentence = match.group().strip()
                            sentence_index += 1
                            if not 8 <= len(sentence) <= 96 or not KANJI.search(sentence):
                                context = sentence
                                continue
                            if re.search(r'https?://|```|[<>]|[\ue000-\uf8ff]', sentence):
                                context = sentence
                                continue
                            if sum('ぁ' <= c <= '鿿' for c in sentence) / len(sentence) < .5:
                                context = sentence
                                continue
                            key = normalize(sentence)
                            if key in sentences_seen or key in reserved:
                                counts['duplicate_or_reserved_sentence'] += 1
                                context = sentence
                                continue
                            try:
                                tokens = orthographic_tokens(sentence, tagger)
                            except ValueError as error:
                                counts['reading_review_rejected'] += 1
                                rejected.write(json.dumps({'document_id': doc_id, 'text': sentence,
                                                           'reason': str(error)}, ensure_ascii=False) + '\n')
                                context = sentence
                                continue
                            rows = []
                            for row in expand(sentence, context, tokens, doc_id, sentence_index, provenance, rng):
                                texts = [row['target'].get('text', '')] + [v for v in row['input'].values() if isinstance(v, str)]
                                if any(normalize(s) in reserved for s in texts if s):
                                    counts['reserved_example'] += 1
                                    continue
                                fingerprint = hashlib.sha256(json.dumps([row['task'], row['input'], row['target']],
                                                    sort_keys=True, ensure_ascii=False).encode()).digest()
                                if fingerprint in examples_seen:
                                    counts['duplicate_example'] += 1
                                    continue
                                examples_seen.add(fingerprint)
                                rows.append(row)
                            if rows:
                                sentences_seen.add(key)
                                for row in rows:
                                    handles[partition].write(json.dumps(row, ensure_ascii=False) + '\n')
                                    counts[f'{partition}_{row["task"]}'] += 1
                                counts['accepted_sentences'] += 1
                                accepted += 1
                            context = sentence
                            if accepted >= 8 or counts['accepted_sentences'] >= args.max_sentences:
                                break
                        if accepted >= 8 or counts['accepted_sentences'] >= args.max_sentences:
                            break
                    if sum(counts[k] for k in ('train_documents', 'val_documents')) % 1000 == 0:
                        print(json.dumps(dict(counts)), flush=True)
                    if counts['accepted_sentences'] >= args.max_sentences:
                        break
            if counts['accepted_sentences'] >= args.max_sentences:
                break
    finally:
        for handle in [*handles.values(), sealed, rejected]:
            handle.close()
    evidence = args.out / 'source-evidence'
    shutil.copytree(args.source, evidence, ignore=shutil.ignore_patterns('data', 'audit'))
    dictionary = Path(unidic_lite.DICDIR)
    shutil.copy2(dictionary / 'BSD', evidence / 'UniDic-BSD')
    shutil.copy2(dictionary / 'COPYING', evidence / 'UniDic-COPYING')
    retain_labeler_licenses(evidence)
    sources = {SOURCE_ID: {
        'license': 'Apache-2.0', 'origin': acquisition['dataset'] + '@' + acquisition['revision'],
        'evidence': 'source-evidence/README.md, LICENSE, ATTRIBUTION.md, acquisition-manifest.json',
        'reviewed_by': 'Codex: published licenses, upstream generation chain and fixed source hashes checked 2026-10-29',
        'approved_uses': ['training', 'weight_distribution'], 'approved_weight_license': 'Apache-2.0',
        'weight_license_review': 'Publisher and upstream Apache-2.0; both named generator models Apache-2.0; notices retained',
        'teacher': {'model': 'cyberagent/calm3-22b-chat + Qwen/Qwen2.5-32B-Instruct',
                    'revision': 'original generation revisions unspecified by publisher; reviewed revisions in acquisition-manifest',
                    'license': 'Apache-2.0', 'output_terms_evidence': 'upstream-README.md and generator model cards/LICENSE'},
        'reading_labeler': {'fugashi': importlib.metadata.version('fugashi'),
                            'unidic_lite': importlib.metadata.version('unidic-lite'),
                            'dictionary_sha256': digest(dictionary / 'sys.dic'), 'dictionary_license': 'BSD-3-Clause'},
        'quality': 'automatically labeled pilot; category sampling still required',
    }}
    write_json(args.out / 'sources.json', sources)
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-origin-v1', 'counts': dict(counts), 'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'builder_sha256': digest(Path(__file__)), 'evaluation_hashes': {str(p): digest(p) for p in EVALUATION_FILES},
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'limitations': ['Automatic orthographic readings not human-certified', 'Noise is synthetic, not real IME event rendering',
                       'Bottom-8 5-gram/Jaccard retrieval is approximate, not semantic decontamination',
                       'Sealed documents logically withheld; OS access control not configured',
                       'Source compiler is trusted for published rights; no per-sentence rights guarantee'],
    })
    write_json(args.sealed_out / 'manifest.json', {'purpose': 'withheld source documents; not opened for model selection',
        'documents': counts['sealed_documents'], 'sha256': digest(args.sealed_out / 'documents.jsonl'),
        'source_revision': acquisition['revision'], 'builder_sha256': digest(Path(__file__))})
    print(json.dumps(dict(counts), indent=2), flush=True)


if __name__ == '__main__':
    main()
