"""Compare two runs of the same ordered Windows trial dictionary cases."""

import argparse
import json
import math
from collections import Counter
from pathlib import Path


def compare(baseline, additional):
    def identities(cases):
        return [(row["group"], row["id"], row["style"], row["input"], row["expected"])
                for row in cases]

    if identities(baseline) != identities(additional):
        raise ValueError("The runs must use the same ordered cases")
    expected_counts = {"sudachi": 15, "wikidata": 15, "control": 60}
    case_counts = Counter(row["group"] for row in baseline)
    bulk = "bulk" in case_counts
    if bulk:
        expected_counts["bulk"] = 180
    if "collision" in case_counts:
        expected_counts["collision"] = 30
    if case_counts != expected_counts:
        raise ValueError(f"Expected case counts {expected_counts}")
    top1 = {group: {"baselineCorrect": 0, "additionalCorrect": 0} for group in expected_counts}
    top5 = {group: {"baselineCorrect": 0, "additionalCorrect": 0} for group in expected_counts}
    changes, regressions, collision_regressions = [], [], []

    def p95(cases):
        samples = sorted(event["ms"] for row in cases for event in row["events"])
        return samples[math.ceil(len(samples) * 0.95) - 1]

    def matches(candidate, group, expected):
        if candidate is None:
            return False
        if group in ("bulk", "collision"):
            term = expected.removesuffix("について調べます")
            return candidate.startswith(term + "について")
        return candidate == expected

    for before, after in zip(baseline, additional, strict=True):
        candidates_before = before["events"][-1]["top5"]
        candidates_after = after["events"][-1]["top5"]
        first_before = next(iter(candidates_before), None)
        first_after = next(iter(candidates_after), None)
        group, expected = after["group"], after["expected"]
        top1[group]["baselineCorrect"] += matches(first_before, group, expected)
        top1[group]["additionalCorrect"] += matches(first_after, group, expected)
        top5[group]["baselineCorrect"] += any(matches(candidate, group, expected) for candidate in candidates_before)
        top5[group]["additionalCorrect"] += any(matches(candidate, group, expected) for candidate in candidates_after)
        if first_before != first_after:
            change = {"id": after["id"], "group": group, "expected": expected,
                      "baseline": first_before, "additional": first_after}
            changes.append(change)
            if group == "control" and first_before == expected:
                regressions.append(change)
            if (group == "collision" and matches(first_before, group, expected)
                    and not any(matches(candidate, group, expected) for candidate in candidates_after)):
                collision_regressions.append(change)

    gains = [top1[group]["additionalCorrect"] - top1[group]["baselineCorrect"]
             for group in ("sudachi", "wikidata")]
    if bulk:
        improved = top5["bulk"]["additionalCorrect"] > top5["bulk"]["baselineCorrect"]
    else:
        improved = min(gains) > 0 and sum(gains) >= 5
    before_p95, after_p95 = p95(baseline), p95(additional)
    latency_budget = max(2.0, before_p95 * 0.1)
    return {
        "passedRpc": improved and not regressions and not collision_regressions
        and after_p95 <= before_p95 + latency_budget,
        "top1": top1,
        "top5": top5,
        "controlRegressions": regressions,
        "collisionRegressions": collision_regressions,
        "top1Changes": changes,
        "appendP95Ms": {"baseline": before_p95, "additional": after_p95,
                        "allowedIncrease": latency_budget},
        "scope": "RPC top-1 and append latency. TSF auto-commit requires a separate live check.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("additional", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    before = json.loads(args.baseline.read_text(encoding="utf-8"))["cases"]
    after = json.loads(args.additional.read_text(encoding="utf-8"))["cases"]
    summary = compare(before, after)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(summary, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(summary["top1"], summary["appendP95Ms"], summary["passedRpc"])
    return 0 if summary["passedRpc"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
