# SPDX-License-Identifier: Apache-2.0
"""Memory-mapped, loss-mask-preserving Origin token sequences.

The raw corpora and millions of Python token lists need not remain resident.
uint16 token IDs are valid only for the explicitly recorded <=65535 vocabulary.
"""
from array import array
import json
import mmap
from pathlib import Path
import struct
import sys

from origin_contract import digest, write_json

INDEX = struct.Struct('<QIHBx')  # byte offset, sequence length, prompt length, task ID


def write_partition(rows, codec, directory, partition, max_length, task_names):
    if sys.byteorder != 'little' or codec.sp.get_piece_size() > 65535 or max_length > 65535:
        raise ValueError('Cache requires little endian, uint16 vocabulary and bounded prompt length')
    directory = Path(directory)
    token_path, index_path = directory / f'{partition}.tokens', directory / f'{partition}.index'
    lengths, targets, count = 0, 0, 0
    by_task = {}
    with token_path.open('xb') as tokens, index_path.open('xb') as index:
        for row in rows:
            example = codec.example(row, max_length)
            ids, labels = example['input_ids'], example['labels']
            prompt = next(i for i, label in enumerate(labels) if label != -100)
            task_id = task_names.index(row['task'])
            index.write(INDEX.pack(tokens.tell(), len(ids), prompt, task_id))
            tokens.write(array('H', ids).tobytes())
            lengths += len(ids)
            targets += len(ids) - prompt
            count += 1
            by_task[row['task']] = by_task.get(row['task'], 0) + 1
            if count % 100000 == 0:
                print(f'{partition}: encoded {count:,} rows', flush=True)
    return {'rows': count, 'processed_tokens_one_pass': lengths, 'target_tokens_one_pass': targets,
            'by_task': by_task, 'tokens_sha256': digest(token_path), 'index_sha256': digest(index_path),
            'token_bytes': token_path.stat().st_size, 'index_bytes': index_path.stat().st_size}


class TokenCache:
    def __init__(self, directory, partition, verify_hashes=True):
        self.directory = Path(directory)
        self.manifest = json.loads((self.directory / 'cache-manifest.json').read_text(encoding='utf-8'))
        if self.manifest.get('schema') != 'origin-token-cache-v1' or sys.byteorder != 'little':
            raise ValueError('Unsupported token cache')
        self.meta = self.manifest['partitions'][partition]
        token_path, index_path = self.directory / f'{partition}.tokens', self.directory / f'{partition}.index'
        if verify_hashes and (digest(token_path) != self.meta['tokens_sha256'] or digest(index_path) != self.meta['index_sha256']):
            raise ValueError('Token cache identity mismatch')
        self.token_file, self.index_file = token_path.open('rb'), index_path.open('rb')
        self.tokens = mmap.mmap(self.token_file.fileno(), 0, access=mmap.ACCESS_READ)
        self.index = mmap.mmap(self.index_file.fileno(), 0, access=mmap.ACCESS_READ)
        if len(self.index) != self.meta['rows'] * INDEX.size or len(self.tokens) % 2:
            self.close()
            raise ValueError('Token cache shape mismatch')

    def __len__(self):
        return self.meta['rows']

    def __getitem__(self, item):
        if not 0 <= item < len(self):
            raise IndexError(item)
        offset, length, prompt, task = INDEX.unpack_from(self.index, item * INDEX.size)
        if offset + length * 2 > len(self.tokens) or not 0 < prompt < length:
            raise ValueError('Corrupt token cache bounds/loss mask')
        ids = array('H')
        ids.frombytes(self.tokens[offset:offset + length * 2])
        values = ids.tolist()
        if values[-1] != self.manifest['eos_id'] or task >= len(self.manifest['task_names']):
            raise ValueError('Corrupt EOS/task ID in cache')
        return {'input_ids': values, 'labels': [-100] * prompt + values[prompt:]}

    def lengths_and_tasks(self):
        lengths, tasks = array('I'), array('B')
        for _, length, _, task in INDEX.iter_unpack(self.index):
            lengths.append(length)
            tasks.append(task)
        return lengths, tasks

    def close(self):
        for name in ('tokens', 'index', 'token_file', 'index_file'):
            value = getattr(self, name, None)
            if value is not None:
                value.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
