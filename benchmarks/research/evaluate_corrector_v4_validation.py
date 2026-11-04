"""Select quantization on the fixed held-out cohort, without development tests."""
import argparse
import json
from pathlib import Path

from benchmark_kana_kanji_models import LlamaServerInstance, sha256


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-name", default="corrector-v4-nano-20261030-r1")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--base-port", type=int, default=8460)
    p.add_argument("--reference", action="append", default=[], help="Additional validation reference NAME=MODEL")
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest_path = Path("artifacts/checkpoints") / f"{args.run_name}-lora/run-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cohort = manifest["validation_cohort"]
    results = {}
    specs = {"v32-baseline": Path("artifacts/models/corrector-v32-xsmall-q4km.gguf")}
    specs.update({key: Path("artifacts/models") / f"{args.run_name}-{key}.gguf"
                  for key in ("f16", "q4km", "q6k", "q8_0", "iq4nl-emb8")})
    for spec in args.reference:
        key, model = spec.split("=", 1)
        if key in specs:
            raise ValueError(f"Duplicate reference: {key}")
        specs[key] = Path(model)
    for key, model in specs.items():
        server = LlamaServerInstance(str(model.resolve()), args.base_port, 0, "cpu")
        cases = []
        try:
            server.start()
            for _ in range(3):
                server.complete("\uee00テスト\uee01")
            for row in cohort:
                response = server.complete(row["prompt"])
                output = response["content"]
                stopped_eos = response.get("stopped_eos", False) or response.get("stop_type") == "eos"
                cases.append({**row, "output": output, "eos": stopped_eos,
                              "correct": stopped_eos and output == row["completion"]})
        finally:
            server.stop()
        scores = {name: {"total": sum(r["clean"] == clean for r in cases),
                         "correct": sum(r["clean"] == clean and r["correct"] for r in cases)}
                  for name, clean in (("clean", True), ("typo", False))}
        balanced = sum(s["correct"] / s["total"] for s in scores.values()) / 2
        results[key] = {"model": str(model), "sha256": sha256(model), "bytes": model.stat().st_size,
                        "scores": scores, "balanced_exact": balanced, "cases": cases,
                        "runtime_sha256": sha256(server.server_exe)}
        print(key, scores, balanced, flush=True)
    # Choose a deployable compact candidate using validation only; baseline/F16
    # remain reference points. Prefer smaller files on an exact score tie.
    selected = max((k for k in ("q4km", "q6k", "q8_0", "iq4nl-emb8")),
                   key=lambda k: (results[k]["balanced_exact"], -results[k]["bytes"]))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"manifest_sha256": sha256(manifest_path), "selected": selected,
        "protocol": {"threads": 4, "backend": "cpu", "temperature": 0, "max_tokens": 96,
                     "selection": "max balanced exact on fixed validation; smaller artifact breaks ties"},
        "models": results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Selected:", selected, flush=True)


if __name__ == "__main__":
    main()
