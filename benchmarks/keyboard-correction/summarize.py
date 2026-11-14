"""Summarize converter ON/OFF cases emitted by run.ps1."""
import argparse
import json
import statistics
from pathlib import Path


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction))]


def summarize(path):
    evaluation = json.loads(path.read_text(encoding="utf-8-sig"))
    cases = evaluation["cases"]
    normal = [case for case in cases if case["clean"]]
    positive = [case for case in cases if not case["clean"]]
    missed = [case for case in positive if case["expected"] != next(iter(case["after"]), "")]
    groups = {}
    for name in sorted({case.get("group", "unspecified") for case in positive}):
        subset = [case for case in positive if case.get("group", "unspecified") == name]
        groups[name] = {
            "count": len(subset),
            "beforeTop1": sum(case["before"][:1] == [case["expected"]] for case in subset),
            "afterTop1": sum(case["after"][:1] == [case["expected"]] for case in subset),
            "afterTop5": sum(case["expected"] in case["after"] for case in subset),
        }
    return {
        "model": evaluation["model"],
        "total": len(cases),
        "positive": len(positive),
        "positiveTop1": len(positive) - len(missed),
        "positiveTop5": sum(case["expected"] in case["after"] for case in positive),
        "positiveGroups": groups,
        "normal": len(normal),
        "normalReadingChanges": sum(bool(case["correctedReading"]) for case in normal),
        "normalTouchReadingChanges": sum(bool(case["touchReading"]) for case in normal),
        "normalSurfaceChanges": sum(case["before"][:1] != case["after"][:1] for case in normal),
        "beforeMedianMs": statistics.median(case["beforeMs"] for case in cases),
        "afterMedianMs": statistics.median(case["afterMs"] for case in cases),
        "pairedDeltaMedianMs": statistics.median(case["afterMs"] - case["beforeMs"] for case in cases),
        "touchExtraMedianMs": statistics.median(case["touchMs"] for case in normal),
        "touchExtraP95Ms": percentile([case["touchMs"] for case in normal], 0.95),
        "missedTop1": [{key: case[key] for key in ["keys", "observedReading", "expected", "after"]} for case in missed],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", type=Path)
    arguments = parser.parse_args()
    print(json.dumps(summarize(arguments.cases), ensure_ascii=True, indent=2))
