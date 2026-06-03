#!/usr/bin/env python3
"""
Project Veto — Veto Task Evaluation Report

JSON Schema for the evaluation report produced by evaluate.py.
"""
EVAL_REPORT_SCHEMA = {
    "type": "object",
    "properties": {
        "model_path": {"type": "string"},
        "dataset_path": {"type": "string"},
        "timestamp": {"type": "string"},
        "total_samples": {"type": "integer"},
        "gbnf_compliance": {
            "type": "object",
            "properties": {
                "valid_json_count": {"type": "integer"},
                "valid_json_rate": {"type": "number"},
                "grammar_adherent_count": {"type": "integer"},
                "grammar_adherent_rate": {"type": "number"}
            }
        },
        "decision_accuracy": {
            "type": "object",
            "properties": {
                "correct": {"type": "integer"},
                "total": {"type": "integer"},
                "accuracy": {"type": "number"}
            }
        },
        "redaction_accuracy": {
            "type": "object",
            "properties": {
                "true_positives": {"type": "integer"},
                "false_positives": {"type": "integer"},
                "false_negatives": {"type": "integer"},
                "precision": {"type": "number"},
                "recall": {"type": "number"},
                "f1": {"type": "number"}
            }
        },
        "structural_validation": {
            "type": "object",
            "properties": {
                "correct": {"type": "integer"},
                "total": {"type": "integer"},
                "accuracy": {"type": "number"}
            }
        },
        "per_sample": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "index": {"type": "integer"},
                    "instruction": {"type": "string"},
                    "expected": {"type": "string"},
                    "predicted": {"type": "string"},
                    "gbnf_valid": {"type": "boolean"},
                    "decision_match": {"type": "boolean"},
                    "task_type": {"type": "string"}
                }
            }
        }
    }
}
