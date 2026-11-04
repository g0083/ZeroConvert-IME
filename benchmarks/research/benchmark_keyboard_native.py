"""CPU Q4/F16 validation of reading priors with a persistent native evaluator."""
import argparse
import json
import math
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

from dataset_isolation import load_jsonl, sha256
from keyboard_hybrid import kata


class NativeScorer:
    def __init__(self, executable, model, runtime, tokenizer, log):
        from transformers import AutoTokenizer
        self.tokenizer = AutoTokenizer.from_pretrained(str(tokenizer), local_files_only=True)
        env = dict(os.environ)
        env['PATH'] = str(runtime.resolve()) + os.pathsep + env.get('PATH', '')
        self.log = log.open('x', encoding='utf-8')
        self.proc = subprocess.Popen([str(executable.resolve()), str(model.resolve())], env=env,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
                                     text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW)
        self.lines = queue.Queue()
        def read_lines():
            for line in self.proc.stdout:
                self.lines.put(line)
            self.lines.put(None)
        self.reader = threading.Thread(target=read_lines, daemon=True)
        self.reader.start()
        try:
            ready = self.read()
            if ready != f'ready {len(self.tokenizer)}':
                raise RuntimeError('Tokenizer vocabulary size mismatch: ' + ready)
        except Exception:
            self.close()
            raise

    def read(self):
        try:
            line = self.lines.get(timeout=30)
        except queue.Empty as exc:
            raise TimeoutError('Native evaluator timed out') from exc
        if line is None:
            raise RuntimeError(f'Native evaluator stopped: {self.proc.poll()}')
        return line.strip()

    def score(self, candidates):
        readings = list(dict.fromkeys(c['reading'] for c in candidates))
        prefix = self.tokenizer.encode('\uee00', add_special_tokens=False)
        sequences = [prefix+self.tokenizer.encode(kata(r)+'\uee01', add_special_tokens=False) for r in readings]
        request = str(len(sequences))+'\n'+''.join(f'{len(s)} {len(prefix)} '+' '.join(map(str, s))+'\n' for s in sequences)
        self.proc.stdin.write(request)
        self.proc.stdin.flush()
        scores = [float(s) for s in self.read().split()]
        if len(scores) != len(readings) or not all(math.isfinite(s) for s in scores):
            raise ValueError('Invalid native scores')
        lookup = {r: {'lm_sum': s, 'lm_mean': s/(len(t)-len(prefix)), 'answer_tokens': len(t)-len(prefix)}
                  for r, s, t in zip(readings, scores, sequences)}
        return [lookup[c['reading']] for c in candidates]

    def close(self):
        if self.proc.poll() is None:
            self.proc.stdin.close()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        self.reader.join(timeout=1)
        self.log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidates', type=Path, required=True)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--tokenizer', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--limit', type=int)
    parser.add_argument('--executable', type=Path, default=Path('artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe'))
    parser.add_argument('--runtime', type=Path, default=Path('ZeroConvertIME-windows/llama_cpu'))
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    rows = load_jsonl(args.candidates)
    candidate_meta = json.loads(args.candidates.with_suffix('.metadata.json').read_text(encoding='utf-8'))
    if candidate_meta['candidate_sha256'] != sha256(args.candidates):
        raise ValueError('Candidate file hash mismatch')
    if args.limit:
        rows = rows[:args.limit]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    scorer = NativeScorer(args.executable, args.model, args.runtime, args.tokenizer, args.output.with_suffix('.log'))
    try:
        # Prime batch allocation; excluded from the measured samples.
        scorer.score(rows[0]['candidates'])
        with args.output.open('x', encoding='utf-8') as out:
            for i, row in enumerate(rows):
                started = time.perf_counter()
                scores = scorer.score(row['candidates'])
                elapsed = (time.perf_counter()-started)*1000
                row['candidates'] = [c | s for c, s in zip(row['candidates'], scores)]
                row['score_ms'] = elapsed
                out.write(json.dumps(row, ensure_ascii=False)+'\n')
                if i % 50 == 0:
                    out.flush()
                    print(f'native {i}/{len(rows)} {elapsed:.1f}ms', flush=True)
    finally:
        scorer.close()
    meta = {'model': str(args.model), 'model_sha256': sha256(args.model), 'model_bytes': args.model.stat().st_size,
            'executable_sha256': sha256(args.executable),
            'runtime_hashes': {p.name: sha256(p) for p in args.runtime.glob('*.dll')},
            'tokenizer_hashes': {p.name: sha256(p) for p in args.tokenizer.iterdir() if p.suffix in ('.json', '.txt')},
            'generation': candidate_meta['generation'],
            'native_protocol': 'masked-prefix-v2',
            'mode': 'reading-prior', 'threads': 4, 'device': 'cpu', 'limit': args.limit,
            'candidate_sha256': sha256(args.candidates), 'result_sha256': sha256(args.output)}
    args.output.with_suffix('.metadata.json').write_text(json.dumps(meta, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
