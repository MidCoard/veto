#!/usr/bin/env python3
"""
Project Veto — Training Data Quality Filter (Feature 6.3)
==========================================================
Validates training data JSONL before it enters the fine-tuning pipeline.

Checks:
  1. JSONL format (each line is valid JSON with required fields)
  2. Output JSON schema conformance (veto_decision, secrets_found, valid)
  3. Instruction length bounds
  4. Deduplication by (instruction, output) pair

Usage:
    python quality_filter.py --data ../data/veto_training_data.jsonl
    python quality_filter.py --data ../data/veto_training_data.jsonl --output ../data/veto_training_data_filtered.jsonl
"""
import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path

# ── Constants ──

REQUIRED_FIELDS = {"id", "task", "instruction", "output"}
VALID_TASKS = {"veto_decision", "redaction", "structural_constraint"}
VALID_VETO_DECISIONS = {"pass", "redact", "block"}
MIN_INSTRUCTION_LENGTH = 10
MAX_INSTRUCTION_LENGTH = 4096


def parse_args():
    parser = argparse.ArgumentParser(description="Veto Training Data Quality Filter")
    parser.add_argument("--data", type=str, required=True,
                        help="Path to training data JSONL to validate")
    parser.add_argument("--output", type=str, default=None,
                        help="Path to write filtered (valid only) JSONL. "
                             "Default: <input>_filtered.jsonl")
    parser.add_argument("--report", type=str, default=None,
                        help="Path to write quality report JSON")
    parser.add_argument("--min-instruction-length", type=int, default=MIN_INSTRUCTION_LENGTH)
    parser.add_argument("--max-instruction-length", type=int, default=MAX_INSTRUCTION_LENGTH)
    parser.add_argument("--fail-on-invalid", action="store_true", default=False,
                        help="Exit with code 1 if any invalid records found")
    return parser.parse_args()


def validate_record(record: dict, idx: int, args) -> list[str]:
    """Validate a single training record. Returns list of error messages (empty = valid)."""
    errors = []

    # 1. Required fields
    missing = REQUIRED_FIELDS - set(record.keys())
    if missing:
        errors.append(f"Missing required fields: {missing}")
        return errors  # Can't validate further without required fields

    # 2. Task type
    task = record.get("task", "")
    if task not in VALID_TASKS:
        errors.append(f"Invalid task type: '{task}'. Expected one of {VALID_TASKS}")

    # 3. Instruction length
    instruction = record.get("instruction", "")
    if len(instruction) < args.min_instruction_length:
        errors.append(f"Instruction too short: {len(instruction)} chars "
                      f"(min {args.min_instruction_length})")
    if len(instruction) > args.max_instruction_length:
        errors.append(f"Instruction too long: {len(instruction)} chars "
                      f"(max {args.max_instruction_length})")

    # 4. Output JSON schema validation
    output_str = record.get("output", "")
    try:
        output_json = json.loads(output_str)
    except json.JSONDecodeError as e:
        errors.append(f"Output is not valid JSON: {e}")
        return errors

    # 4a. veto_decision task
    if task == "veto_decision":
        if "veto_decision" not in output_json:
            errors.append("Missing 'veto_decision' in output")
        elif output_json["veto_decision"] not in VALID_VETO_DECISIONS:
            errors.append(f"Invalid veto_decision: '{output_json['veto_decision']}'. "
                          f"Expected one of {VALID_VETO_DECISIONS}")
        if "data" not in output_json:
            errors.append("Missing 'data' object in output")
        elif not isinstance(output_json["data"], dict):
            errors.append("'data' must be a JSON object")
        else:
            data = output_json["data"]
            if "reason" not in data:
                errors.append("Missing 'reason' in data")
            if "confidence" in data:
                conf = data["confidence"]
                if not isinstance(conf, (int, float)) or not (0 <= conf <= 1):
                    errors.append(f"Invalid confidence: {conf} (must be 0-1)")

    # 4b. redaction task
    elif task == "redaction":
        if "secrets_found" not in output_json:
            errors.append("Missing 'secrets_found' in output")
        elif not isinstance(output_json["secrets_found"], bool):
            errors.append(f"'secrets_found' must be boolean, got {type(output_json['secrets_found']).__name__}")
        if "redacted_fields" not in output_json:
            errors.append("Missing 'redacted_fields' in output")
        elif not isinstance(output_json["redacted_fields"], list):
            errors.append("'redacted_fields' must be an array")

    # 4c. structural_constraint task
    elif task == "structural_constraint":
        if "valid" not in output_json:
            errors.append("Missing 'valid' in output")
        elif not isinstance(output_json["valid"], bool):
            errors.append(f"'valid' must be boolean, got {type(output_json['valid']).__name__}")
        if "violations" not in output_json:
            errors.append("Missing 'violations' in output")
        elif not isinstance(output_json["violations"], list):
            errors.append("'violations' must be an array")

    return errors


def run_quality_filter(args) -> dict:
    """Run the quality filter and return the report."""
    data_path = Path(args.data)
    if not data_path.exists():
        print(f"[FATAL] Data file not found: {data_path}")
        sys.exit(1)

    # Read all records
    records = []
    with open(data_path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
                records.append((line_num, record, line))
            except json.JSONDecodeError as e:
                records.append((line_num, None, line))

    total = len(records)
    valid_records = []
    invalid_records = []
    seen_pairs = set()
    duplicates = 0

    # Validate each record
    for line_num, record, raw_line in records:
        if record is None:
            invalid_records.append({
                "line": line_num,
                "id": None,
                "errors": ["Line is not valid JSON"],
            })
            continue

        errors = validate_record(record, line_num, args)

        # Deduplication by (instruction, output)
        pair_key = (record.get("instruction", ""), record.get("output", ""))
        if pair_key in seen_pairs:
            duplicates += 1
            errors.append("Duplicate (instruction, output) pair")

        if errors:
            invalid_records.append({
                "line": line_num,
                "id": record.get("id", "unknown"),
                "errors": errors,
            })
        else:
            seen_pairs.add(pair_key)
            valid_records.append(record)

    # Task distribution
    task_counts = Counter(r.get("task", "unknown") for r in valid_records)

    # Build report
    report = {
        "input_file": str(data_path),
        "total_records": total,
        "valid_records": len(valid_records),
        "invalid_records": len(invalid_records),
        "duplicates_removed": duplicates,
        "task_distribution": dict(task_counts),
        "invalid_details": invalid_records[:50],  # Cap at 50 for readability
        "status": "pass" if len(valid_records) > 0 else "fail",
    }

    # Write filtered output
    output_path = Path(args.output) if args.output else data_path.parent / f"{data_path.stem}_filtered{data_path.suffix}"
    with open(output_path, "w", encoding="utf-8") as f:
        for record in valid_records:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    report["output_file"] = str(output_path)

    # Write report
    report_path = Path(args.report) if args.report else data_path.parent / "quality_report.json"
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    # Print summary
    print(f"\n{'='*60}")
    print(f"QUALITY FILTER — {data_path.name}")
    print(f"{'='*60}")
    print(f"  Total records:     {total}")
    print(f"  Valid records:     {len(valid_records)}")
    print(f"  Invalid records:   {len(invalid_records)}")
    print(f"  Duplicates:        {duplicates}")
    print(f"  Task distribution: {dict(task_counts)}")
    print(f"  Status:            {report['status'].upper()}")
    print(f"  Filtered output:   {output_path}")
    print(f"  Report:            {report_path}")
    print(f"{'='*60}")

    if invalid_records:
        print(f"\n  First {min(10, len(invalid_records))} invalid records:")
        for inv in invalid_records[:10]:
            print(f"    Line {inv['line']} (id={inv['id']}): {'; '.join(inv['errors'])}")

    return report


if __name__ == "__main__":
    args = parse_args()
    report = run_quality_filter(args)
    if args.fail_on_invalid and report["invalid_records"] > 0:
        sys.exit(1)
