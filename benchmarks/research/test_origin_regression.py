# SPDX-License-Identifier: Apache-2.0
"""Ensure the Origin adapter extension preserves legacy prompts and scoring."""
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_kana_kanji_models import LlamaServerInstance, evaluate_model
from benchmark_origin_regression import normalized_hash_paths


class OriginRegressionTests(unittest.TestCase):
    def test_frozen_windows_and_posix_evaluation_paths(self):
        self.assertEqual(normalized_hash_paths({r'data\case.jsonl': 'a', 'data/other.jsonl': 'b'}),
                         {'data/case.jsonl': 'a', 'data/other.jsonl': 'b'})
        with self.assertRaises(ValueError):
            normalized_hash_paths({r'data\case.jsonl': 'a', 'data/case.jsonl': 'b'})

    def test_default_prompt_remains_byte_identical(self):
        server = LlamaServerInstance('unused', 0)
        self.assertEqual(server.conversion_prompt('会議室の', 'ヨヤク'), '\uee02会議室の\uee00ヨヤク\uee01')

    def test_injected_server_uses_identical_case_scorer_and_protocol(self):
        with tempfile.TemporaryDirectory(dir='artifacts/results') as temporary:
            artifact = Path(temporary) / 'identity.txt'
            artifact.write_text('test-only', encoding='utf-8')
            class FakeServer:
                def __init__(self, *args):
                    self.server_exe = str(artifact)
                    self.requests = 0
                def start(self):
                    pass
                def stop(self):
                    pass
                def conversion_prompt(self, context, reading):
                    return [1, 4, 9]  # The scorer is independent of prompt representation.
                def complete(self, prompt):
                    self.requests += 1
                    if self.requests <= 3:
                        return {'content': 'warmup'}
                    return {'content': '予約', 'stop_type': 'eos'}
            result = evaluate_model(str(artifact), 0, [{'id': 'test', 'left_context': '会議室の',
                'target': 'よやく', 'expected': '予約'}], 0, 'cpu', server_factory=FakeServer)
            self.assertEqual(result['perfect_count'], 1)
            self.assertEqual(result['details'][0]['score'], 2)
            self.assertEqual(result['protocol']['context'], 2048)
            self.assertEqual(result['protocol']['threads'], 4)
            self.assertFalse(result['protocol']['cache_prompt'])


if __name__ == '__main__':
    unittest.main()
