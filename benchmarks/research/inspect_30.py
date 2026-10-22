# -*- coding: utf-8 -*-
import json
from research.benchmark_typo_full_comparison import LlamaServerInstance

with open("data/typo_benchmark_300.jsonl", "r", encoding="utf-8") as f:
    cases = [json.loads(line) for line in f if line.strip()][:30]

srv = LlamaServerInstance('artifacts/models/typo-corrector-xsmall-q4km.gguf', 8097)
srv.start()

results = []
try:
    for c in cases:
        if not c["is_clean"]:
            p = srv.complete(c["prompt"])
            results.append((c["input_reading"], c["expected"], p, p == c["expected"]))
finally:
    srv.stop()

with open("scratch/inspect_30_xsmall.txt", "w", encoding="utf-8") as f:
    for inp, exp, pred, match in results:
        f.write(f"Inp: {inp}\n  Exp:  {exp}\n  Pred: {pred}\n  Match: {match}\n\n")

print("Done writing scratch/inspect_30_xsmall.txt")
