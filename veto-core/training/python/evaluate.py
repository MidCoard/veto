#!/usr/bin/env python3
"""
Project Veto — Model Evaluation Script
=======================================
Loads a trained GGUF model and evaluates it against the evaluation dataset.

Measures:
  - GBNF grammar compliance rate (output matches expected JSON structure)
  - Redaction accuracy (precision, recall, F1)
  - Decision accuracy (pass/redact/block)
  - Structural validation accuracy

Usage:
    python evaluate.py --model ../models/veto-slm-q4_k_m.gguf --data ../data/veto_eval_data.jsonl
"""
import argparse
import json
import os
import re
import time
import sys
from pathlib import Path
from datetime import datetime

# Import eval schema
sys.path.insert(0, os.path.dirname(__file__))
from eval_schema import EVAL_REPORT_SCHEMA


def parse_args():
    parser = argparse.ArgumentParser(description="Veto SLM Evaluation")
    parser.add_argument("--model", type=str, required=True,
                        help="Path to GGUF model file")
    parser.add_argument("--data", type=str, required=True,
                        help="Path to evaluation data JSONL")
    parser.add_argument("--grammar", type=str, default=None,
                        help="Path to GBNF grammar file (optional, for compliance check)")
    parser.add_argument("--n-predict", type=int, default=512,
                        help="Max tokens to generate")
    parser.add_argument("--temperature", type=float, default=0.1,
                        help="Inference temperature (lower = more deterministic)")
    parser.add_argument("--output", type=str, default=None,
                        help="Path to write evaluation report JSON")
    parser.add_argument("--max-samples", type=int, default=None,
                        help="Limit samples for faster testing")
    parser.add_argument("--json-output", action="store_true", default=True,
                        help="Write machine-readable JSON report (maps to Java EvaluationReport)")
    return parser.parse_args()


def load_gguf_model(model_path: str):
    """Load a GGUF model using llama-cpp-python."""
    try:
        from llama_cpp import Llama
    except ImportError:
        print("[FATAL] llama-cpp-python not installed. Run: pip install llama-cpp-python>=0.3.0")
        sys.exit(1)

    if not os.path.isfile(model_path):
        print(f"[FATAL] Model not found: {model_path}")
        sys.exit(1)

    print(f"Loading model: {model_path} "
          f"({os.path.getsize(model_path) / 1e9:.2f} GB) ...")
    llm = Llama(
        model_path=model_path,
        n_ctx=2048,
        n_gpu_layers=-1,  # Auto GPU offload
        verbose=False,
    )
    print(f"[OK] Model loaded (vocab_size={llm.n_vocab() if hasattr(llm, 'n_vocab') else 'N/A'})")
    return llm


def load_eval_data(data_path: str, max_samples: int | None = None) -> list[dict]:
    """Load evaluation dataset from JSONL."""
    records = []
    with open(data_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    if max_samples:
        records = records[:max_samples]
    print(f"[OK] Loaded {len(records)} evaluation records")
    return records


def build_prompt(record: dict) -> str:
    """Build the instruction prompt for the model."""
    return f"### Instruction:\n{record['instruction']}\n\n### Response:\n"


def parse_json_output(text: str) -> dict | None:
    """Extract and parse JSON from model output."""
    # Try to find JSON object in output
    json_match = re.search(r'\{[^{}]*\}', text, re.DOTALL)
    if json_match:
        try:
            return json.loads(json_match.group())
        except json.JSONDecodeError:
            pass

    # Try balanced brace matching for nested JSON
    stack = []
    start = -1
    for i, c in enumerate(text):
        if c == '{':
            if not stack:
                start = i
            stack.append(c)
        elif c == '}':
            if stack:
                stack.pop()
                if not stack and start >= 0:
                    try:
                        return json.loads(text[start:i+1])
                    except json.JSONDecodeError:
                        break
    return None


def validate_gbnf_compliance(output_json: dict) -> bool:
    """Check if output JSON matches the expected GBNF grammar structure."""
    required_keys = ["veto_decision"] if "veto_decision" in output_json else []
    required_keys += ["secrets_found"] if "secrets_found" in output_json else []
    required_keys += ["valid"] if "valid" in output_json else []

    if "veto_decision" in output_json:
        if output_json["veto_decision"] not in ("pass", "redact", "block"):
            return False
        if "data" not in output_json:
            return False

    if "secrets_found" in output_json:
        if not isinstance(output_json["secrets_found"], bool):
            return False
        if "redacted_fields" not in output_json:
            return False

    if "valid" in output_json:
        if not isinstance(output_json["valid"], bool):
            return False
        if "violations" not in output_json:
            return False

    return True


def evaluate_decision(record: dict, output_json: dict) -> bool:
    """Check if the veto_decision matches expected output."""
    expected_raw = json.loads(record["output"])
    if "veto_decision" in expected_raw and "veto_decision" in output_json:
        return expected_raw["veto_decision"] == output_json["veto_decision"]
    return True  # Non-decision tasks skip this metric


def evaluate_redaction(record: dict, output_json: dict) -> tuple[int, int, int]:
    """Evaluate redaction accuracy: (tp, fp, fn)."""
    expected_raw = json.loads(record["output"])

    if "secrets_found" not in expected_raw:
        return (0, 0, 0)

    expected_found = expected_raw.get("secrets_found", False)
    actual_found = output_json.get("secrets_found", False)

    expected_fields = {f["field"] for f in expected_raw.get("redacted_fields", [])}
    actual_fields = {f.get("field", "") for f in output_json.get("redacted_fields", [])}

    if not expected_found and not actual_found:
        return (0, 0, 0)
    if not expected_found and actual_found:
        return (0, len(actual_fields), 0)
    if expected_found and not actual_found:
        return (0, 0, len(expected_fields))

    tp = len(expected_fields & actual_fields)
    fp = len(actual_fields - expected_fields)
    fn = len(expected_fields - actual_fields)
    return (tp, fp, fn)


def evaluate_structural(record: dict, output_json: dict) -> bool:
    """Check structural constraint validation accuracy."""
    expected_raw = json.loads(record["output"])
    if "valid" in expected_raw and "valid" in output_json:
        return expected_raw["valid"] == output_json["valid"]
    return True


def main():
    args = parse_args()

    # ── Load model ──
    llm = load_gguf_model(args.model)

    # ── Load data ──
    records = load_eval_data(args.data, args.max_samples)

    # ── Results ──
    results = []
    total_gbnf_valid = 0
    total_decision_correct = 0
    total_decision_count = 0
    total_structural_correct = 0
    total_structural_count = 0
    total_tp = 0
    total_fp = 0
    total_fn = 0

    print(f"\n{'='*60}")
    print(f"Running evaluation on {len(records)} samples ...")
    print(f"{'='*60}\n")

    start_time = time.time()

    for i, record in enumerate(records):
        prompt = build_prompt(record)

        # Generate
        output = llm(
            prompt,
            max_tokens=args.n_predict,
            temperature=args.temperature,
            stop=["###"],
            echo=False,
        )
        generated_text = output["choices"][0]["text"].strip() if output.get("choices") else ""

        # Parse
        parsed = parse_json_output(generated_text)
        is_gbnf_valid = parsed is not None and validate_gbnf_compliance(parsed)

        task_type = record.get("task", "unknown")

        decision_match = False
        redaction_tp = redaction_fp = redaction_fn = 0
        structural_match = False

        if parsed:
            decision_match = evaluate_decision(record, parsed)
            redaction_tp, redaction_fp, redaction_fn = evaluate_redaction(record, parsed)
            structural_match = evaluate_structural(record, parsed)

        # Update totals
        if is_gbnf_valid:
            total_gbnf_valid += 1
        if decision_match:
            total_decision_correct += 1
        if task_type == "veto_decision" or task_type == "redaction":
            total_decision_count += 1 if "veto_decision" in record.get("output", "") else 0
        total_tp += redaction_tp
        total_fp += redaction_fp
        total_fn += redaction_fn
        if structural_match:
            total_structural_correct += 1
        if task_type == "structural_constraint":
            total_structural_count += 1

        # Per-sample result
        result_entry = {
            "index": i,
            "task_type": task_type,
            "gbnf_valid": is_gbnf_valid,
            "decision_match": decision_match,
            "expected_json": json.loads(record["output"]),
            "predicted_json": parsed,
            "predicted_raw": generated_text[:200] + ("..." if len(generated_text) > 200 else ""),
        }
        results.append(result_entry)

        if (i + 1) % 10 == 0:
            elapsed = time.time() - start_time
            rate = (i + 1) / elapsed
            print(f"  [{i+1}/{len(records)}] {rate:.1f} samples/s | "
                  f"GBNF: {total_gbnf_valid}/{i+1} "
                  f"({100*total_gbnf_valid/(i+1):.0f}%)")

    elapsed = time.time() - start_time
    n = len(records)
    gbnf_rate = total_gbnf_valid / n if n > 0 else 0
    decision_acc = total_decision_correct / total_decision_count if total_decision_count > 0 else 0
    structural_acc = total_structural_correct / total_structural_count if total_structural_count > 0 else 0
    precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0
    recall = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0

    # ── Build report (structure mirrors Java TrainingProgress.EvaluationReport) ──
    report = {
        "modelPath": args.model,
        "datasetPath": args.data,
        "timestamp": datetime.utcnow().isoformat(),
        "totalSamples": n,
        "elapsedSeconds": round(elapsed, 1),
        "gbnfCompliance": {
            "validJsonCount": total_gbnf_valid,
            "validJsonRate": round(gbnf_rate, 4),
        },
        "decisionAccuracy": {
            "correct": total_decision_correct,
            "total": total_decision_count,
            "accuracy": round(decision_acc, 4),
        },
        "redactionAccuracy": {
            "truePositives": total_tp,
            "falsePositives": total_fp,
            "falseNegatives": total_fn,
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(f1, 4),
        },
        "structuralValidation": {
            "correct": total_structural_correct,
            "total": total_structural_count,
            "accuracy": round(structural_acc, 4),
        },
        # Per-sample details omitted from machine-readable output (too large for Java ingestion)
        # Kept only in the full human-readable report
    }

    # ── Print summary ──
    print(f"\n{'='*60}")
    print(f"EVALUATION COMPLETE — {n} samples in {elapsed:.1f}s")
    print(f"{'='*60}")
    print(f"  GBNF Compliance:     {total_gbnf_valid}/{n} ({gbnf_rate:.1%})")
    print(f"  Decision Accuracy:   {total_decision_correct}/{total_decision_count} ({decision_acc:.1%})")
    print(f"  Structural Accuracy: {total_structural_correct}/{total_structural_count} ({structural_acc:.1%})")
    print(f"  Redaction Precision: {precision:.3f}")
    print(f"  Redaction Recall:    {recall:.3f}")
    print(f"  Redaction F1:        {f1:.3f}")
    print(f"{'='*60}")

    # ── Write report ──
    output_path = args.output or os.path.join(
        os.path.dirname(args.model), f"eval_report_{int(time.time())}.json"
    )
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"[OK] Report written to {output_path}")

    return report


if __name__ == "__main__":
    main()
