#!/usr/bin/env python3
"""
Project Veto — QLoRA Fine-Tuning Pipeline
==========================================
Fine-tunes a lightweight Qwen model for veto-specific structured output tasks.

Usage:
    python train.py \\
        --base-model Qwen/Qwen2.5-1.5B-Instruct \\
        --data-path ../data/veto_training_data.jsonl \\
        --output-dir ../models/fine-tuned

Environment:
    Auto-creates/uses a venv at training/.venv/ if VETO_VENV is set.
    Expects Python dependencies from requirements.txt.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

# ── Quiet HF warnings before imports ──
os.environ["TRANSFORMERS_VERBOSITY"] = "error"
os.environ["TOKENIZERS_PARALLELISM"] = "false"


def parse_args():
    parser = argparse.ArgumentParser(description="Veto SLM QLoRA Fine-Tuning")
    parser.add_argument("--base-model", type=str, default="Qwen/Qwen2.5-1.5B-Instruct",
                        help="HuggingFace model ID or local path")
    parser.add_argument("--data-path", type=str, required=True,
                        help="Path to training data JSONL")
    parser.add_argument("--output-dir", type=str, default="../models/fine-tuned",
                        help="Directory to save fine-tuned model")
    parser.add_argument("--eval-data-path", type=str, default=None,
                        help="Path to evaluation data JSONL")
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=2)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--lora-dropout", type=float, default=0.05)
    parser.add_argument("--max-seq-length", type=int, default=1024)
    parser.add_argument("--hf-token", type=str, default=None,
                        help="HuggingFace token for gated models")
    parser.add_argument("--log-file", type=str, default="training_log.jsonl")
    parser.add_argument("--quality-filter", action="store_true", default=False,
                        help="Run quality filter on input data before training")
    parser.add_argument("--structured-output", action="store_true", default=True,
                        help="Emit structured JSON progress lines to stdout (for Java parser)")
    return parser.parse_args()


def emit_progress(event_type: str, **kwargs):
    """Emit a structured JSON progress line to stdout (parseable by TrainingManager)."""
    import time as _time
    msg = {"type": event_type, "timestamp": _time.time()}
    msg.update(kwargs)
    print(json.dumps(msg), flush=True)


def run_quality_filter(data_path: str) -> bool:
    """Run quality_filter.py on the training data. Returns True if data passes."""
    filter_script = Path(__file__).parent / "quality_filter.py"
    if not filter_script.exists():
        print("[WARN] quality_filter.py not found, skipping quality filter")
        return True

    import subprocess
    result = subprocess.run(
        [sys.executable, str(filter_script), "--data", data_path, "--fail-on-invalid"],
        capture_output=True, text=True,
    )
    print(result.stdout)
    if result.returncode != 0:
        print(f"[ERROR] Quality filter failed:\n{result.stderr}")
        return False
    return True


def setup_environment(args):
    """Validate and prepare environment before training."""
    data_path = Path(args.data_path)
    if not data_path.exists():
        print(f"[FATAL] Training data not found: {data_path}")
        sys.exit(1)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Check GPU
    try:
        import torch
        if torch.cuda.is_available():
            print(f"[OK] GPU: {torch.cuda.get_device_name(0)} "
                  f"(VRAM: {torch.cuda.get_device_properties(0).total_mem / 1e9:.1f} GB)")
        else:
            print("[WARN] No GPU detected. Training will be very slow on CPU. "
                  "Consider a smaller model like Qwen2.5-0.5B.")
    except ImportError:
        print("[FATAL] PyTorch not installed. Run: pip install torch>=2.4.0")
        sys.exit(1)

    # Check PEFT
    try:
        import peft  # noqa
        print("[OK] PEFT available")
    except ImportError:
        print("[FATAL] PEFT not installed. Run: pip install peft>=0.12.0")
        sys.exit(1)

    # Check bitsandbytes
    try:
        import bitsandbytes  # noqa
        print("[OK] bitsandbytes available (4-bit QLoRA)")
    except ImportError:
        print("[FATAL] bitsandbytes not installed. Run: pip install bitsandbytes>=0.44.0")
        sys.exit(1)

    return out_dir


def load_dataset(data_path: Path, eval_path: Path | None) -> tuple:
    """Load JSONL training data into HuggingFace Dataset."""
    from datasets import Dataset, DatasetDict

    records = []
    with open(data_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))

    print(f"[OK] Loaded {len(records)} training records from {data_path.name}")

    train_dataset = Dataset.from_list(records)

    evals = []
    if eval_path and eval_path.exists():
        with open(eval_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    evals.append(json.loads(line))
        print(f"[OK] Loaded {len(evals)} evaluation records from {eval_path.name}")
    else:
        # Split off 5% for eval if no separate file
        split = max(1, len(records) // 20)
        train_dataset = Dataset.from_list(records[:-split])
        evals = records[-split:]
        print(f"[WARN] No eval file; splitting off {len(evals)} records for evaluation")

    eval_dataset = Dataset.from_list(evals) if evals else None

    return DatasetDict({"train": train_dataset, "eval": eval_dataset}) if eval_dataset else DatasetDict({"train": train_dataset})


def format_instruction(record: dict) -> str:
    """Format a training record as instruction-output text."""
    return f"### Instruction:\n{record['instruction']}\n\n### Response:\n{record['output']}"


def tokenize_function(examples, tokenizer, max_length: int):
    """Tokenize instruction-output pairs."""
    texts = [format_instruction({"instruction": inst, "output": out})
             for inst, out in zip(examples["instruction"], examples["output"])]
    tokenized = tokenizer(
        texts,
        truncation=True,
        padding="max_length",
        max_length=max_length,
        return_tensors=None,
    )
    tokenized["labels"] = tokenized["input_ids"].copy()
    return tokenized


def train(args):
    """Main training loop."""
    out_dir = setup_environment(args)

    # ── Quality Filter (Feature 6.3) ──
    if args.quality_filter:
        emit_progress("phase", phase="quality_filter", message="Running quality filter on training data...")
        if not run_quality_filter(args.data_path):
            emit_progress("error", message="Quality filter failed — training data contains invalid records")
            sys.exit(1)
        emit_progress("phase_complete", phase="quality_filter", message="Quality filter passed")

    import torch
    from transformers import (
        AutoModelForCausalLM, AutoTokenizer, TrainingArguments, Trainer,
        BitsAndBytesConfig
    )
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

    # ── Structured progress callback ──
    from transformers import TrainerCallback

    class StructuredProgressCallback(TrainerCallback):
        """Emits structured JSON progress lines during training (for Java TrainingManager parser)."""
        def on_log(self, args, state, control, logs=None, **kwargs):
            if logs and args.structured_output:
                emit_progress("progress",
                              epoch=state.epoch or 0,
                              step=state.global_step,
                              max_steps=state.max_steps,
                              loss=logs.get("loss", logs.get("eval_loss", None)),
                              learning_rate=logs.get("learning_rate", None))

        def on_epoch_begin(self, args, state, control, **kwargs):
            if args.structured_output:
                emit_progress("epoch_start", epoch=int(state.epoch or 0) + 1)

        def on_epoch_end(self, args, state, control, **kwargs):
            if args.structured_output:
                emit_progress("epoch_end", epoch=int(state.epoch or 0) + 1)

    # ── Quantization config (4-bit QLoRA) ──
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    # ── Load tokenizer ──
    print(f"Loading tokenizer from {args.base_model} ...")
    tokenizer = AutoTokenizer.from_pretrained(
        args.base_model,
        trust_remote_code=True,
        token=args.hf_token,
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # ── Load model with 4-bit quantization ──
    print(f"Loading base model {args.base_model} with 4-bit QLoRA ...")
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
        token=args.hf_token,
        torch_dtype=torch.bfloat16,
    )
    model = prepare_model_for_kbit_training(model)

    # ── LoRA config ──
    lora_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    # ── Load dataset ──
    eval_path = Path(args.eval_data_path) if args.eval_data_path else None
    datasets = load_dataset(Path(args.data_path), eval_path)

    tokenized_datasets = datasets.map(
        lambda x: tokenize_function(x, tokenizer, args.max_seq_length),
        batched=True,
        remove_columns=datasets["train"].column_names,
    )

    # ── Training arguments ──
    run_name = f"veto-slm-{int(time.time())}"
    training_args = TrainingArguments(
        output_dir=str(out_dir),
        run_name=run_name,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        gradient_checkpointing=True,
        learning_rate=args.lr,
        weight_decay=0.01,
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        logging_steps=5,
        save_strategy="epoch",
        evaluation_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        bf16=torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8,
        fp16=not (torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8),
        report_to="none",
        dataloader_num_workers=2,
        remove_unused_columns=False,
    )

    # ── Trainer ──
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_datasets["train"],
        eval_dataset=tokenized_datasets.get("eval"),
        tokenizer=tokenizer,
        callbacks=[StructuredProgressCallback()] if args.structured_output else [],
    )

    # ── Train ──
    print(f"\n{'='*60}")
    print(f"Starting training: {args.base_model}")
    print(f"  LoRA rank={args.lora_r}, alpha={args.lora_alpha}")
    print(f"  Epochs={args.epochs}, lr={args.lr}, batch={args.batch_size}")
    print(f"  Output: {out_dir}")
    print(f"{'='*60}\n")

    emit_progress("phase", phase="training", message="Starting QLoRA fine-tuning...")
    trainer.train()
    emit_progress("phase_complete", phase="training", message="Training complete")

    # ── Save ──
    model.save_pretrained(str(out_dir / "lora-adapter"))
    tokenizer.save_pretrained(str(out_dir / "lora-adapter"))
    print(f"[OK] LoRA adapter saved to {out_dir / 'lora-adapter'}")

    # Merge LoRA weights into base model (optional, for GGUF conversion)
    print("Merging LoRA adapter into base model ...")
    merged = model.merge_and_unload()
    merged.save_pretrained(str(out_dir / "merged"))
    tokenizer.save_pretrained(str(out_dir / "merged"))
    print(f"[OK] Merged model saved to {out_dir / 'merged'}")

    # ── Log summary ──
    log_entry = {
        "base_model": args.base_model,
        "output_dir": str(out_dir),
        "epochs": args.epochs,
        "learning_rate": args.lr,
        "lora_r": args.lora_r,
        "lora_alpha": args.lora_alpha,
        "batch_size": args.batch_size,
        "gradient_accumulation_steps": args.gradient_accumulation_steps,
        "train_samples": len(datasets["train"]),
        "eval_samples": len(datasets.get("eval", [])),
        "status": "completed",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    log_path = Path(args.log_file)
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(log_entry) + "\n")

    print(f"\n{'='*60}")
    print("Training complete!")
    print(f"Merged model: {out_dir / 'merged'}")
    print(f"LoRA adapter: {out_dir / 'lora-adapter'}")
    print(f"Next: python convert_to_gguf.py --model-dir {out_dir / 'merged'}")
    print(f"{'='*60}")

    return str(out_dir / "merged")


if __name__ == "__main__":
    train(parse_args())
