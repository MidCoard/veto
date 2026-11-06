"""Score native Secret Agent traces; no model inference or credential execution.

Predictions are JSONL {id, tool_calls: [OpenAI function calls], completed: bool}.
completed means the evaluated agent finished with DONE after successful imports.
Use the same held-out export and execution settings for baseline and SFT traces.
"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

IMPORT = "plugin_top_focess_secret_protection__import_credential"
FIELDS = ("value", "visibility", "service", "label")


def arguments(call):
    if not isinstance(call, dict):
        return None
    function = call.get("function")
    if not isinstance(function, dict) or function.get("name") != IMPORT:
        return None
    args = function.get("arguments")
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except ValueError:
            return None
    if (not isinstance(args, dict) or set(args) != set(FIELDS)
            or any(not isinstance(args[key], str) or not args[key] for key in FIELDS)
            or args["visibility"] not in ("PRIVATE", "PUBLIC")):
        return None
    return args


def scores(tp, fp, fn):
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {"truePositives": tp, "falsePositives": fp, "falseNegatives": fn,
            "precision": precision, "recall": recall,
            "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0}


def evaluate(records, predictions):
    tp = fp = fn = exact = completed = invalid = benign = alarm = public_errors = 0
    argument_tp = argument_fp = argument_fn = 0
    seen = set()
    failures = []
    count = 0
    for record, prediction in zip(records, predictions, strict=True):
        identity = record["id"]
        if identity in seen or prediction.get("id") != identity:
            raise ValueError("Predictions must match unique dataset IDs in order")
        seen.add(identity)
        expected = [arguments(call) for message in record["messages"]
                    for call in (message.get("tool_calls") or [])]
        if any(args is None for args in expected):
            raise ValueError("Invalid gold import arguments")
        calls = prediction.get("tool_calls")
        if not isinstance(calls, list) or type(prediction.get("completed")) is not bool:
            raise ValueError("Trace requires tool_calls array and completed boolean")
        parsed = [arguments(call) for call in calls]
        bad = sum(args is None for args in parsed)
        actual = [args for args in parsed if args is not None]
        gold_values = Counter(args["value"] for args in expected)
        values = Counter(args["value"] for args in actual)
        tp += sum((gold_values & values).values())
        fp += sum((values - gold_values).values())
        fn += sum((gold_values - values).values())
        gold_args = Counter(tuple(args[key] for key in FIELDS) for args in expected)
        actual_args = Counter(tuple(args[key] for key in FIELDS) for args in actual)
        argument_tp += sum((gold_args & actual_args).values())
        argument_fp += sum((actual_args - gold_args).values()) + bad
        argument_fn += sum((gold_args - actual_args).values())
        gold_public = Counter(args["value"] for args in expected if args["visibility"] == "PUBLIC")
        actual_public = Counter(args["value"] for args in actual if args["visibility"] == "PUBLIC")
        public_errors += sum((actual_public - gold_public).values())
        invalid += bad
        completed += prediction["completed"]
        correct = not bad and prediction["completed"] and actual_args == gold_args
        exact += correct
        benign += not expected
        alarm += not expected and bool(calls)
        if not correct and len(failures) < 100:
            failures.append(identity)
        count += 1
    if not count:
        raise ValueError("Empty evaluation")
    return {"samples": count, "exactValues": scores(tp, fp, fn),
            "importArguments": scores(argument_tp, argument_fp, argument_fn),
            "exactWorkflowRate": exact / count, "completionRate": completed / count,
            "invalidCalls": invalid, "falsePublicImports": public_errors,
            "benignSamples": benign, "benignFalseAlarmRate": alarm / benign if benign else 0.0,
            "firstFailureIds": failures}


def read_rows(path):
    if path.stat().st_size > 400_000_000:
        raise ValueError("Evaluation input exceeds 400 MB")
    with path.open(encoding="utf-8") as source:
        for line in source:
            yield json.loads(line)


def checksum(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("report", type=Path)
    options = parser.parse_args()
    result = evaluate(read_rows(options.data), read_rows(options.predictions))
    result.update(datasetSha256=checksum(options.data), predictionsSha256=checksum(options.predictions))
    with options.report.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2)
