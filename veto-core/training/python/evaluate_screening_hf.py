#!/usr/bin/env python3
"""Evaluate a merged Hugging Face screening model before GGUF conversion."""

import argparse
import json
import time
from collections import Counter
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-new-tokens", type=int, default=128)
    return parser.parse_args()


def extract_json(text):
    start = text.find("{")
    if start < 0:
        return None
    try:
        value, _ = json.JSONDecoder().raw_decode(text[start:])
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        return None


def main():
    args = parse_args()
    records = [json.loads(line) for line in args.data.read_text(encoding="utf-8").splitlines() if line.strip()]
    if (args.model / "adapter_config.json").exists():
        from peft import PeftConfig, PeftModel
        from transformers import BitsAndBytesConfig

        peft_config = PeftConfig.from_pretrained(args.model)
        tokenizer = AutoTokenizer.from_pretrained(args.model)
        base = AutoModelForCausalLM.from_pretrained(
            peft_config.base_model_name_or_path,
            device_map="auto",
            quantization_config=BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",
                bnb_4bit_compute_dtype=torch.bfloat16,
                bnb_4bit_use_double_quant=True,
            ),
        )
        model = PeftModel.from_pretrained(base, args.model)
    else:
        tokenizer = AutoTokenizer.from_pretrained(args.model)
        model = AutoModelForCausalLM.from_pretrained(
            args.model,
            device_map="auto",
            dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
        )
    model.eval()

    valid = relevance_correct = danger_correct = joint_correct = 0
    relevance_confusion = Counter()
    danger_confusion = Counter()
    details = []
    started = time.time()
    for index, record in enumerate(records, start=1):
        expected = json.loads(record["output"])
        prompt = f"### Instruction:\n{record['instruction']}\n\n### Response:\n"
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        with torch.inference_mode():
            generated = model.generate(
                **inputs,
                max_new_tokens=args.max_new_tokens,
                do_sample=False,
                pad_token_id=tokenizer.eos_token_id,
            )
        response_tokens = generated[0, inputs["input_ids"].shape[1]:]
        raw = tokenizer.decode(response_tokens, skip_special_tokens=True).strip()
        predicted = extract_json(raw)
        rel = predicted.get("relevance") if predicted else None
        danger = predicted.get("danger") if predicted else None
        if predicted:
            valid += 1
        rel_match = rel == expected["relevance"]
        danger_match = danger == expected["danger"]
        relevance_correct += int(rel_match)
        danger_correct += int(danger_match)
        joint_correct += int(rel_match and danger_match)
        relevance_confusion[(expected["relevance"], rel or "INVALID")] += 1
        danger_confusion[(expected["danger"], danger or "INVALID")] += 1
        details.append(
            {
                "id": record["id"],
                "expected": expected,
                "predicted": predicted,
                "raw": raw,
            }
        )
        print(f"[{index}/{len(records)}] relevance={rel} danger={danger}", flush=True)

    total = len(records)
    report = {
        "modelPath": str(args.model.resolve()),
        "datasetPath": str(args.data.resolve()),
        "totalSamples": total,
        "elapsedSeconds": round(time.time() - started, 2),
        "validJsonRate": valid / total,
        "relevanceAccuracy": relevance_correct / total,
        "dangerAccuracy": danger_correct / total,
        "jointAccuracy": joint_correct / total,
        "relevanceConfusion": {f"{key[0]}->{key[1]}": value for key, value in sorted(relevance_confusion.items())},
        "dangerConfusion": {f"{key[0]}->{key[1]}": value for key, value in sorted(danger_confusion.items())},
        "details": details,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("validJsonRate", "relevanceAccuracy", "dangerAccuracy", "jointAccuracy")}, indent=2))


if __name__ == "__main__":
    main()
