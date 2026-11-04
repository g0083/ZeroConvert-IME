# SPDX-License-Identifier: Apache-2.0
"""Append-only v4 vocabulary and integer-ID multitask codec.

Text is encoded without special-token recognition. Missing byte alphabet entries
are appended before control tokens, making whitespace and arbitrary UTF-8 lossless.
The legacy codec remains a separate, explicitly lossy compatibility condition.
"""
import json
from pathlib import Path

from tokenizers import Tokenizer
from transformers import AutoTokenizer, PreTrainedTokenizerFast

from origin_contract import digest, write_json

BASE_VOCAB = 6000
FIELDS = ('ctx', 'reading', 'raw', 'pending', 'surface', 'cursor', 'events', 'protected')
CONTROLS = ([f'<task_{t}>' for t in ('convert', 'repair', 'predict')] +
            [f'<method_{m}>' for m in ('romaji', 'flick', 'kana')] +
            [f'<{f}>' for f in FIELDS] + ['<layout>', '<out>', '<keep>', '<abstain>'])


def byte_alphabet():
    values = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    result = dict(zip(values, map(chr, values)))
    for byte in range(256):
        if byte not in result:
            result[byte] = chr(256 + sum(c not in values for c in range(byte)))
    return [result[i] for i in range(256)]


def hira(text):
    return ''.join(chr(ord(c) - 96) if 'ァ' <= c <= 'ヶ' else c for c in text)


def kata(text):
    return ''.join(chr(ord(c) + 96) if 'ぁ' <= c <= 'ゖ' else c for c in text)


def build_tokenizer(parent, out, repair_emit_marker=False, observation_table=None, observation_license=None,
                    compact=False, repair_input='raw-observed', repair_output='reading'):
    if repair_input not in ('raw-observed', 'observed') or repair_output not in ('reading', 'surface'):
        raise ValueError('Unsupported repair contract')
    if not compact and (repair_input != 'raw-observed' or repair_output != 'reading'):
        raise ValueError('Alternative repair contracts require the versioned compact codec')
    out = Path(out)
    if out.exists():
        raise FileExistsError(out)
    original = AutoTokenizer.from_pretrained(parent, local_files_only=True)
    if len(original) != BASE_VOCAB or (original.bos_token_id, original.eos_token_id, original.pad_token_id) != (2, 3, 1):
        raise ValueError('Expected original v4 vocabulary')
    spec = json.loads(original.backend_tokenizer.to_str())
    vocab = spec['model']['vocab']
    for piece in byte_alphabet():
        if piece not in vocab:
            vocab[piece] = len(vocab)
    byte_count = len(vocab) - BASE_VOCAB
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=Tokenizer.from_str(json.dumps(spec)),
        bos_token='<s>', eos_token='</s>', pad_token='[PAD]', unk_token='[UNK]',
        clean_up_tokenization_spaces=False, model_max_length=1024)
    controls = CONTROLS + (['<emit>'] if repair_emit_marker else [])
    if compact:
        if not repair_emit_marker:
            raise ValueError('Compact codec requires explicit repair emit')
        controls += ['<missing>']
    tokenizer.add_special_tokens({'additional_special_tokens': controls})
    if any(tokenizer.get_vocab().get(k) != v for k, v in original.get_vocab().items()):
        raise ValueError('Original IDs changed')
    tokenizer.save_pretrained(out)
    # The GPT-2 converter also expects the traditional BPE vocabulary files.
    tokenizer.backend_tokenizer.model.save(str(out))
    observation = None
    if observation_table:
        from keyboard_hybrid import RomanDecoder
        import shutil
        if not observation_license or not Path(observation_license).is_file():
            raise ValueError('Pinned table license must accompany the observation decoder')
        decoder = RomanDecoder(Path(observation_table))
        write_json(out / 'qwerty-observation-table.json', decoder.mapping)
        shutil.copy2(observation_license, out / 'qwerty-observation-MIT-LICENSE')
        observation = {'mapping_sha256': digest(out / 'qwerty-observation-table.json'),
            'source_table_sha256': digest(observation_table), 'license_sha256': digest(observation_license),
            'meaning': 'deterministic observed reading of raw minus pending; not reference/gold reading',
            'limits': 'offline pinned table, not a TSF event-state oracle'}
    metadata = {
        'schema': 'zerocon-v4-unified-codec-v1', 'base_vocab': BASE_VOCAB,
        'vocab_size': len(tokenizer), 'appended_byte_tokens': byte_count,
        'controls': {t: tokenizer.convert_tokens_to_ids(t) for t in controls},
        'repair_emit_marker': repair_emit_marker, 'repair_observation': observation,
        'fields': list(FIELDS), 'parent_tokenizer_files': {
            p.name: digest(p) for p in Path(parent).iterdir()
            if p.name in ('vocab.json', 'merges.txt', 'tokenizer_config.json', 'special_tokens_map.json')},
        'cursor_units': 'Unicode code points into visible surface; missing is explicit',
        'supported_repair_layouts': ['JP-QWERTY', 'US-QWERTY'],
        'legacy_spaces': 'Historical ASCII=0/fullwidth=567; lossy condition only',
    }
    if compact:
        metadata.update({'schema': 'zerocon-v4-unified-codec-v2', 'compact': True,
                         'repair_input': repair_input, 'repair_output': repair_output,
                         'strings': 'verbatim data IDs; no JSON quoting; explicit missing control'})
    write_json(out / 'v4-unified-codec.json', metadata)
    return V4UnifiedCodec(out)


class V4UnifiedCodec:
    def __init__(self, directory):
        directory = Path(directory)
        self.metadata = json.loads((directory / 'v4-unified-codec.json').read_text(encoding='utf-8'))
        self.tokenizer = AutoTokenizer.from_pretrained(directory, local_files_only=True)
        self.ids = self.metadata['controls']
        if len(self.tokenizer) != self.metadata['vocab_size'] or any(
                self.tokenizer.convert_tokens_to_ids(k) != v for k, v in self.ids.items()):
            raise ValueError('Codec IDs do not match tokenizer')
        spec = json.loads(self.tokenizer.backend_tokenizer.to_str())
        spec['added_tokens'] = []
        self.data_tokenizer = Tokenizer.from_str(json.dumps(spec))
        self.eos_id, self.pad_id = 3, 1
        self.observation_mapping = None
        if self.metadata.get('repair_observation'):
            path = directory / 'qwerty-observation-table.json'
            if digest(path) != self.metadata['repair_observation']['mapping_sha256']:
                raise ValueError('Observation mapping changed')
            self.observation_mapping = json.loads(path.read_text(encoding='utf-8'))

    def observed_reading(self, raw, pending):
        if self.observation_mapping is None:
            raise ValueError('This codec has no observation decoder')
        if pending and not raw.endswith(pending):
            raise ValueError('Pending must be the observed raw suffix')
        keys = raw[:-len(pending)] if pending else raw
        output, index = [], 0
        is_kana = lambda value: value and all('ぁ' <= c <= 'ゖ' or c == 'ー' for c in value)
        while index < len(keys):
            if index+1 < len(keys) and keys[index] == keys[index+1] and keys[index] not in 'aeioun':
                output.append('っ')
                index += 1
                continue
            for width in range(min(4, len(keys)-index), 0, -1):
                value = self.observation_mapping.get(keys[index:index+width])
                if is_kana(value):
                    output.append(value)
                    index += width
                    break
            else:
                if keys[index] == 'n' and (index+1 == len(keys) or keys[index+1] not in 'aiueoy'):
                    output.append('ん')
                else:
                    output.append('ー' if keys[index] == '-' else keys[index])
                index += 1
        return ''.join(output)

    def text(self, value):
        if not isinstance(value, str):
            raise ValueError('Expected text')
        ids = self.data_tokenizer.encode(value, add_special_tokens=False).ids
        if set(ids) & (set(self.ids.values()) | {0, 1, 2, 3}):
            raise ValueError('Literal text became control IDs')
        if self.data_tokenizer.decode(ids, skip_special_tokens=False) != value:
            raise ValueError(f'Non-lossless text: {value!r}')
        return ids

    def prompt(self, row):
        task, method, layout = row['task'], row['input_method'], row['layout']
        if task not in ('convert', 'repair', 'predict') or method not in ('romaji', 'flick', 'kana'):
            raise ValueError('Unsupported task/method')
        if task == 'repair' and (method != 'romaji' or layout not in ('JP-QWERTY', 'US-QWERTY')):
            raise ValueError('This first model supports QWERTY repair only')
        inputs = dict(row['input'])
        allowed = {'convert': {'ctx', 'reading', 'protected'},
                   'repair': {'ctx', 'raw', 'pending'},
                   'predict': {'ctx', 'surface', 'pending'}}[task]
        observed_only = self.metadata.get('repair_input') == 'observed'
        if task == 'repair' and observed_only:
            allowed = {'ctx', 'reading', 'pending'}
        if set(inputs) - allowed:
            raise ValueError('Unsupported/unobserved input slots')
        required = {'convert': {'ctx', 'reading'}, 'repair': {'ctx', 'raw', 'pending'},
                    'predict': {'ctx', 'surface', 'pending'}}[task]
        if task == 'repair' and observed_only:
            required = {'ctx', 'reading', 'pending'}
        if not required <= set(inputs):
            raise ValueError('Required observation missing')
        if task == 'repair' and not observed_only and self.observation_mapping is not None:
            inputs['reading'] = self.observed_reading(inputs['raw'], inputs['pending'])
        ids = [self.ids[f'<task_{task}>'], self.ids[f'<method_{method}>'], self.ids['<layout>']]
        ids += self.text(layout)
        fields = FIELDS
        if self.metadata.get('compact'):
            fields = {'convert': ('ctx', 'reading', 'protected'),
                      'repair': ('ctx', 'reading', 'pending') if observed_only else ('ctx', 'raw', 'reading', 'pending'),
                      'predict': ('ctx', 'surface', 'pending')}[task]
        for field in fields:
            ids.append(self.ids[f'<{field}>'])
            value = inputs.get(field)
            if field == 'reading' and value is not None:
                value = kata(value)
            if self.metadata.get('compact'):
                if value is None:
                    ids.append(self.ids['<missing>'])
                elif field in ('ctx', 'reading', 'raw', 'pending', 'surface'):
                    ids += self.text(value)
                else:
                    ids += self.text(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')))
                continue
            if value is None:
                # JSON string values are quoted, so missing cannot alias user text.
                value = 'null'
            elif field in ('ctx', 'reading', 'raw', 'pending', 'surface'):
                value = json.dumps(value, ensure_ascii=False)
            else:
                value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
            ids += self.text(value)
        return ids + [self.ids['<out>']]

    def answer(self, row):
        target = row['target']
        action, text = target['action'], target.get('text', '')
        if action not in ('emit', 'keep', 'abstain') or (action == 'emit') != bool(text):
            raise ValueError('Invalid action/text')
        if row['task'] == 'convert' and action != 'emit':
            raise ValueError('Conversion must emit')
        if row['task'] == 'predict' and (action == 'keep' or (row['input']['pending'] and action != 'abstain')):
            raise ValueError('Prediction must respect pending state')
        if row['task'] == 'repair' and action == 'emit' and self.metadata.get('repair_output', 'reading') == 'reading' and any(not ('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ') for c in text):
            raise ValueError('Repair output must be a hiragana reading')
        if self.metadata.get('compact') and row['task'] == 'repair' and row['input'].get('pending') and action != 'abstain':
            raise ValueError('Pending repair must abstain')
        answer = self.text(text) if action == 'emit' else [self.ids[f'<{action}>']]
        if row['task'] == 'repair' and action == 'emit' and self.metadata.get('repair_emit_marker'):
            answer = [self.ids['<emit>']] + answer
        return answer + [self.eos_id]

    def example(self, row, max_length):
        prompt, answer = self.prompt(row), self.answer(row)
        if len(prompt) + len(answer) > max_length:
            raise ValueError('Length exceeds limit; truncation forbidden')
        return {'input_ids': prompt + answer, 'labels': [-100] * len(prompt) + answer}

    def decode_answer(self, ids, task=None):
        if not ids or ids[-1] != self.eos_id:
            return {'action': 'invalid', 'text': '', 'reason': 'missing EOS'}
        body = ids[:-1]
        for action in ('keep', 'abstain'):
            if body == [self.ids[f'<{action}>']]:
                return {'action': action, 'text': ''}
        if self.metadata.get('repair_emit_marker'):
            if body and body[0] == self.ids['<emit>']:
                if task != 'repair':
                    return {'action': 'invalid', 'text': '', 'reason': 'repair marker in another task'}
                body = body[1:]
            elif task == 'repair':
                return {'action': 'invalid', 'text': '', 'reason': 'missing repair action marker'}
        if not body or set(body) & (set(self.ids.values()) | {0, 1, 2, 3}):
            return {'action': 'invalid', 'text': '', 'reason': 'control or empty output'}
        text = self.data_tokenizer.decode(body, skip_special_tokens=False)
        if '\ufffd' in text:
            return {'action': 'invalid', 'text': '', 'reason': 'invalid UTF-8'}
        return {'action': 'emit', 'text': text}


def legacy_example(row, tokenizer):
    """Original v4 contract; do not use this for lossless new task outputs."""
    from train_nano_v3 import encode_with_space_token
    p = '\uee02' + row['input']['ctx'] + '\uee00' + kata(row['input']['reading']) + '\uee01'
    prompt = encode_with_space_token(tokenizer, p, 0)
    answer = encode_with_space_token(tokenizer, row['target']['text'], 567) + [3]
    return {'input_ids': prompt + answer, 'labels': [-100] * len(prompt) + answer}


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--repair-emit-marker', action='store_true')
    parser.add_argument('--observation-table', type=Path)
    parser.add_argument('--observation-license', type=Path)
    parser.add_argument('--compact', action='store_true')
    parser.add_argument('--repair-input', choices=('raw-observed', 'observed'), default='raw-observed')
    parser.add_argument('--repair-output', choices=('reading', 'surface'), default='reading')
    args = parser.parse_args()
    codec = build_tokenizer(args.parent, args.out, args.repair_emit_marker, args.observation_table,
                            args.observation_license, args.compact, args.repair_input, args.repair_output)
    print(json.dumps(codec.metadata, indent=2))
