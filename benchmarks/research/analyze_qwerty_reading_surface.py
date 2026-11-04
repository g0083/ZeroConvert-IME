"""Group-paired accuracy analysis and post-run dependency audit."""
import argparse
import json
from pathlib import Path
import random
import statistics
from analyze_qwerty_corrector import group_values, fscore
from dataset_isolation import sha256
from keyboard_hybrid import DICTIONARY, TABLE
from qwerty_corrector_policy import write_json


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out-dir",type=Path,required=True)
    a=p.parse_args()
    target=a.out_dir/"analysis.json"
    if target.exists(): raise FileExistsError(target)
    comparison=json.loads((a.out_dir/"comparison.json").read_text(encoding="utf-8"))
    if comparison.get("status")!="completed": raise ValueError("Incomplete comparison")
    dependencies=[DICTIONARY,TABLE,Path("ZeroConvertIME-windows/azookey-server.dll")]
    dependencies+=list(Path("artifacts/checkpoints/nano-v4-argon4-merged").glob("*.json"))
    dependencies+=list(Path("ZeroConvertIME-windows/llama_cpu").glob("*.dll"))
    report={"comparison_sha256":sha256(a.out_dir/"comparison.json"),
        "post_run_dependency_audit":{str(p):sha256(p) for p in dependencies},"splits":{}}
    base_dir=Path("artifacts/results/corrector-qwerty-state-20261030-r1-comparison")
    for split in ("test","fresh"):
        fixture=Path("artifacts/results")/("corrector-qwerty-state-20261030-r1-test-fixture.json" if split=="test" else "corrector-qwerty-fresh-20261030-r1-fixture.json")
        rows=json.loads(fixture.read_text(encoding="utf-8"))["cases"]
        baseline=base_dir/f"{split}-previous-qwerty-state-channel.json"
        before=json.loads(baseline.read_text(encoding="utf-8"))["report"]
        after=json.loads((a.out_dir/f"{split}-selected.json").read_text(encoding="utf-8"))["report"]
        groups=[group_values(rows,r) for r in (before,after)]
        keys=sorted(groups[0]); rng=random.Random(42); deltas=[]
        for _ in range(5000):
            sample=rng.choices(keys,k=len(keys))
            values=[fscore([sum(g[k][i] for k in sample) for i in range(3)]) for g in groups]
            deltas.append(values[1]-values[0])
        deltas.sort()
        lookup={r["id"]:r for r in rows}
        counts={"added":0,"added_surface_correct":0,"added_correct_reading_but_surface_mismatch":0,"added_wrong_reading_or_unneeded":0}
        for item in after["cases"]:
            if not item.get("reading_search_added"): continue
            row=lookup[item["id"]]; counts["added"]+=1
            correct=row["expected_action"]=="repair" and item["correction"] in [row["expected"],*row.get("acceptable",[])]
            if correct: counts["added_surface_correct"]+=1
            elif row["expected_action"]=="repair" and item["reading_search_candidate"]["reading"]==row["target_reading"]:
                counts["added_correct_reading_but_surface_mismatch"]+=1
            else: counts["added_wrong_reading_or_unneeded"]+=1
        report["splits"][split]={"groups":len(keys),"baseline_sha256":sha256(baseline),"fixture_sha256":sha256(fixture),
            "paired_f0_5_delta_95_percentile_interval":[deltas[125],deltas[4874]],"bootstrap_draws":5000,"seed":42,
            "macro_group_f0_5_before_after":[statistics.mean(fscore(v) for v in g.values()) for g in groups],"added_breakdown":counts}
    write_json(target,report)
    print(json.dumps(report["splits"],ensure_ascii=False,indent=2))


if __name__=="__main__": main()
