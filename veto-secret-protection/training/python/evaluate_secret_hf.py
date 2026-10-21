"""Matched secret-extraction evaluation with exact-value metrics and no test-set tuning."""

import argparse
import hashlib
import json
import time
from pathlib import Path


def parse_values(raw, text):
    try:
        values = json.loads(raw)
    except (ValueError, TypeError):
        return None
    if not isinstance(values, list) or any(not isinstance(x, str) for x in values):
        return None
    # Invalid/hallucinated values count as false positives, never get silently dropped.
    return set(values)


def metrics(records, responses):
    tp = fp = fn = exact = valid = benign = false_alarm = 0
    rule_tp = rule_fp = rule_fn = union_tp = union_fp = union_fn = 0
    details = []
    for record, raw in zip(records, responses, strict=True):
        expected = set(json.loads(record["output"]))
        values = parse_values(raw, record["text"])
        predicted = values if values is not None else set()
        valid += values is not None
        exact += values is not None and predicted == expected
        tp += len(expected & predicted)
        fp += len(predicted - expected)
        fn += len(expected - predicted)
        if not expected:
            benign += 1
            false_alarm += bool(predicted)
        rules = set(record.get("ruleValues", []))
        union = rules | {v for v in predicted if len(v) >= 4 and v in record["text"]}
        rule_tp += len(rules & expected)
        rule_fp += len(rules - expected)
        rule_fn += len(expected - rules)
        union_tp += len(union & expected)
        union_fp += len(union - expected)
        union_fn += len(expected - union)
        details.append({"id": record["id"], "raw": raw, "correct": values is not None and predicted == expected})

    def scores(hits, extra, missed):
        precision = hits / (hits + extra) if hits + extra else 0.0
        recall = hits / (hits + missed) if hits + missed else 0.0
        return {"precision": precision, "recall": recall,
                "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
                "truePositives": hits, "falsePositives": extra, "falseNegatives": missed}

    return {"samples": len(records), "modelOnly": scores(tp, fp, fn),
            "rulesOnly": scores(rule_tp, rule_fp, rule_fn),
            "rulesPlusValidatedValues": scores(union_tp, union_fp, union_fn),
            "exactMatch": exact / len(records), "validJsonRate": valid / len(records),
            "benignSamples": benign, "benignFalseAlarmRate": false_alarm / benign if benign else 0.0,
            "details": details}


def main():
    # Reuse shared training cache locations only for local model execution.
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "veto-training/python"))
    from runtime_paths import configure_caches

    configure_caches()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    content = args.data.read_bytes()
    records = [json.loads(line) for line in content.decode("utf-8").splitlines() if line.strip()]
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True)
    tokenizer.padding_side = "left"
    model = AutoModelForCausalLM.from_pretrained(
        args.model, device_map="auto", torch_dtype=torch.bfloat16,
        local_files_only=True,
    ).eval()
    responses = []
    started = time.monotonic()
    for start in range(0, len(records), args.batch_size):
        batch = records[start:start + args.batch_size]
        inputs = tokenizer([r["instruction"] for r in batch], padding=True, return_tensors="pt").to(model.device)
        with torch.inference_mode():
            outputs = model.generate(**inputs, do_sample=False, max_new_tokens=128,
                                     pad_token_id=tokenizer.eos_token_id)
        responses.extend(tokenizer.batch_decode(outputs[:, inputs["input_ids"].shape[1]:], skip_special_tokens=True))
        print(f"Evaluated {len(responses)}/{len(records)}", flush=True)
    report = metrics(records, responses)
    report.update({"model": args.model, "datasetSha256": hashlib.sha256(content).hexdigest(),
                   "elapsedSeconds": time.monotonic() - started, "batchSize": args.batch_size,
                   "decoding": "greedy, max_new_tokens=128, raw production MDC prompt, BF16, no grammar",
                   "gpu": torch.cuda.get_device_name(0)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "details"}, indent=2))


if __name__ == "__main__":
    main()
