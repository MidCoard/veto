#!/usr/bin/env python3
"""Merge a LoRA adapter into a fresh, unquantized base model for deployment."""

import argparse
from pathlib import Path

import torch
from peft import PeftConfig, PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    config = PeftConfig.from_pretrained(args.adapter)
    dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32
    base = AutoModelForCausalLM.from_pretrained(
        config.base_model_name_or_path,
        dtype=dtype,
        device_map="cpu",
    )
    model = PeftModel.from_pretrained(base, args.adapter)
    merged = model.merge_and_unload(safe_merge=True)
    args.output.mkdir(parents=True, exist_ok=True)
    merged.save_pretrained(args.output, safe_serialization=True)
    AutoTokenizer.from_pretrained(args.adapter).save_pretrained(args.output)
    print(f"[OK] Unquantized adapter merge saved to {args.output}")


if __name__ == "__main__":
    main()
