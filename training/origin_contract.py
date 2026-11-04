# SPDX-License-Identifier: Apache-2.0
"""ZeroCon-Origin v1: explicit control IDs, lossless data fields, split checks.

Cursor/protected offsets use Python Unicode code points (not TSF UTF-16 units).
No tokenizer sees validation or evaluation text during vocabulary training.
"""
import hashlib
import json
import unicodedata
from pathlib import Path

TASKS = ('convert', 'repair_qwerty', 'repair_flick', 'predict')
FIELDS = ('ctx', 'reading', 'raw', 'pending', 'surface', 'cursor', 'protected')
CONTROLS = [f'<task_{task}>' for task in TASKS] + [
    f'<{field}>' for field in FIELDS
] + ['<out>', '<keep>', '<abstain>']
SIZES = {
    't0': (2, 128, 4, 2, 384),
    'n28': (8, 512, 8, 2, 1536),
    'n33': (8, 512, 8, 2, 1536),
    's68': (10, 768, 12, 2, 1792),
    's88': (12, 768, 12, 4, 2048),
}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def text_key(text):
    # Only for duplicate detection. Never use this as model input.
    return ''.join(unicodedata.normalize('NFKC', text).split())


def read_rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def validate_row(row):
    for field in ('id', 'source_id', 'document_id', 'source_text'):
        if not isinstance(row.get(field), str) or not row[field].strip():
            raise ValueError(f'Missing identity: {field}')
    if row.get('task') not in TASKS:
        raise ValueError('Unknown task')
    inputs = row.get('input')
    target = row.get('target')
    if not isinstance(inputs, dict) or set(inputs) - set(FIELDS):
        raise ValueError('Unsupported input fields')
    for field in ('ctx', 'reading', 'raw', 'pending', 'surface'):
        if not isinstance(inputs.get(field, ''), str):
            raise ValueError(f'{field} must be text')
    if 'cursor' in inputs:
        # Cursor is in the visible, uncommitted surface.
        if type(inputs['cursor']) is not int or not 0 <= inputs['cursor'] <= len(inputs.get('surface', '')):
            raise ValueError('Cursor must be a code-point offset into surface')
    if not isinstance(inputs.get('protected', []), list):
        raise ValueError('Protected regions must be a list')
    for span in inputs.get('protected', []):
        field, start, end = span.get('field'), span.get('start'), span.get('end')
        if field not in ('reading', 'raw', 'surface') or type(start) is not int or type(end) is not int:
            raise ValueError('Invalid protected region')
        if not 0 <= start < end <= len(inputs.get(field, '')) or inputs[field][start:end] != span.get('text'):
            raise ValueError('Protected region does not match source')
    if not isinstance(target, dict) or target.get('action') not in ('emit', 'keep', 'abstain'):
        raise ValueError('Invalid target action')
    if not isinstance(target.get('text', ''), str):
        raise ValueError('Target must be text')
    if target['action'] == 'emit' and not target.get('text'):
        raise ValueError('Empty emit; use abstain')
    if target['action'] != 'emit' and target.get('text'):
        raise ValueError('Control targets cannot contain text')
    if row['task'] == 'convert' and (not inputs.get('reading') or target['action'] != 'emit'):
        raise ValueError('Conversion requires reading and emitted surface')
    if row['task'] == 'predict' and target['action'] == 'keep':
        raise ValueError('Prediction uses abstain, not keep')
    if row['task'] == 'repair_qwerty' and not inputs.get('raw'):
        raise ValueError('QWERTY repair requires original keystrokes')
    if row['task'] == 'repair_flick' and not inputs.get('surface'):
        raise ValueError('Flick repair requires observed kana')
    if row['task'].startswith('repair') and target['action'] == 'emit':
        if any(not ('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ') for c in target['text']):
            raise ValueError('Initial repair contract emits a hiragana reading')


def validate_sources(rows, sources):
    for row in rows:
        validate_row(row)
        source = sources.get(row['source_id'], {})
        if not source.get('license') or not source.get('origin') or not source.get('evidence'):
            raise ValueError(f'Incomplete provenance: {row["source_id"]}')
        if not {'training', 'weight_distribution'} <= set(source.get('approved_uses', [])):
            raise ValueError(f'Unapproved source: {row["source_id"]}')
        # A review record is mandatory, not an automatic legal determination.
        if not source.get('reviewed_by'):
            raise ValueError('Source review record is required')
        if source.get('approved_weight_license') != 'Apache-2.0' or not source.get('weight_license_review'):
            raise ValueError('Explicit Apache-2.0 weight distribution review is required')
        if source.get('teacher') is not None:
            teacher = source['teacher']
            if not all(teacher.get(k) for k in ('model', 'revision', 'license', 'output_terms_evidence')):
                raise ValueError('Incomplete teacher provenance')


def validate_partitions(train, val):
    if not train or not val:
        raise ValueError('Both train and validation must contain source documents')
    seen = {}
    for partition, rows in (('train', train), ('validation', val)):
        for row in rows:
            validate_row(row)
            # Document identity, source sentence, and exact prompt/target duplicates.
            keys = [('document', row['source_id'], row['document_id']),
                    ('source', text_key(row['source_text'])),
                    ('example', row['task'], json.dumps(row['input'], sort_keys=True, ensure_ascii=False),
                     json.dumps(row['target'], sort_keys=True, ensure_ascii=False))]
            for key in keys:
                if key in seen and seen[key] != partition:
                    raise ValueError(f'Cross-partition contamination: {key[0]}')
                seen[key] = partition


def parameter_count(size, vocab):
    layers, width, heads, kv_heads, ffn = SIZES[size]
    kv = kv_heads * (width // heads)
    return vocab * width + layers * (2 * width * width + 2 * width * kv + 3 * width * ffn + 2 * width) + width


class OriginCodec:
    def __init__(self, model_file):
        import sentencepiece as spm
        self.sp = spm.SentencePieceProcessor(model_file=str(model_file))
        self.ids = {tag: self.sp.piece_to_id(tag) for tag in CONTROLS}
        if any(i == self.sp.unk_id() or not self.sp.is_control(i) for i in self.ids.values()):
            raise ValueError('Origin control symbols are missing')
        self.pad_id = self.sp.pad_id()
        self.bos_id, self.eos_id = self.sp.bos_id(), self.sp.eos_id()

    def text(self, value):
        # SP reserves U+2581 internally. Encode this literal as UTF-8 byte pieces.
        # Control symbols in user text are *not* recognized by SP's data encoder.
        parts = value.split('▁')
        ids = []
        for i, part in enumerate(parts):
            if i:
                ids.extend(self.sp.piece_to_id(f'<0x{byte:02X}>') for byte in '▁'.encode())
            ids.extend(self.sp.encode(part, out_type=int))
        if self.sp.unk_id() in ids or set(ids) & set(self.ids.values()):
            raise ValueError('Data became an unknown/control token')
        if self.sp.decode(ids) != value:
            raise ValueError(f'Text is not preserved: {value!r}')
        return ids

    def prompt(self, row):
        ids = [self.bos_id, self.ids[f'<task_{row["task"]}>']]
        for field in FIELDS:
            ids.append(self.ids[f'<{field}>'])
            value = row['input'].get(field, [] if field == 'protected' else '')
            if not isinstance(value, str):
                value = json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
            ids.extend(self.text(value))
        return ids + [self.ids['<out>']]

    def example(self, row, max_length):
        validate_row(row)
        prompt = self.prompt(row)
        target = row['target']
        answer = self.text(target['text']) if target['action'] == 'emit' else [self.ids[f'<{target["action"]}>']]
        ids = prompt + answer + [self.eos_id]
        if len(ids) > max_length:
            raise ValueError(f'Example {row["id"]} exceeds length; EOS truncation is forbidden')
        return {'input_ids': ids, 'labels': [-100] * len(prompt) + answer + [self.eos_id]}

    def decode_answer(self, ids):
        if not ids or ids[-1] != self.eos_id or self.eos_id in ids[:-1]:
            return {'action': 'invalid', 'text': ''}
        body = ids[:-1]
        for action in ('keep', 'abstain'):
            if body == [self.ids[f'<{action}>']]:
                return {'action': action, 'text': ''}
        if not body or any(self.sp.is_control(i) or i in (self.sp.unk_id(), self.pad_id) for i in body):
            return {'action': 'invalid', 'text': ''}
        return {'action': 'emit', 'text': self.sp.decode(body)}
