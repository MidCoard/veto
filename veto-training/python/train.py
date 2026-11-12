#!/usr/bin/env python3
"""Shared QLoRA trainer. Task owners supply JSONL inputs and evaluate exported models."""
import argparse
import json
import os
import sys
import time
from pathlib import Path

from runtime_paths import TRAINING_WORK, configure_caches

configure_caches()

# ── Quiet HF warnings before imports ──
os.environ["TRANSFORMERS_VERBOSITY"] = "error"
os.environ["TOKENIZERS_PARALLELISM"] = "false"


def relative_log_path(target: str | Path, log_file: str | Path) -> str:
    """Serialize a training artifact relative to its local log, without leaking host drives."""
    target_path = Path(target)
    log_path = Path(log_file)
    try:
        relative = os.path.relpath(
            target_path.resolve(strict=False), log_path.parent.resolve(strict=False)
        )
    except ValueError:
        relative = target_path.name
    return Path(relative).as_posix()


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
    parser.add_argument("--eval-batch-size", type=int, default=1,
                        help="Per-device evaluation batch size; kept separate to bound logits memory")
    parser.add_argument("--gradient-accumulation-steps", type=int, default=2)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--lora-dropout", type=float, default=0.05)
    parser.add_argument("--max-seq-length", type=int, default=1024)
    parser.add_argument("--checkpoint-steps", type=int, default=100)
    parser.add_argument("--early-stopping-patience", type=int, default=3,
                        help="0 runs every epoch; positive values stop after non-improving evaluation epochs")
    parser.add_argument("--resume-from-checkpoint", type=str, default=None)
    parser.add_argument("--prompt-format", choices=("instruction", "raw", "native_tools"), default="instruction",
                        help="Use raw for production-compiled MDC prompts")
    parser.add_argument("--hf-token", type=str, default=None,
                        help="HuggingFace token for gated models")
    parser.add_argument("--log-file", type=str, default=str(TRAINING_WORK / "training_log.jsonl"))
    parser.add_argument("--structured-output", action=argparse.BooleanOptionalAction, default=True,
                        help="Emit structured JSON progress lines to stdout (for Java parser)")
    return parser.parse_args()


def emit_progress(event_type: str, **kwargs):
    """Emit a structured JSON progress line to stdout (for offline maintainer logs)."""
    import time as _time
    msg = {"type": event_type, "timestamp": _time.time()}
    msg.update(kwargs)
    print(json.dumps(msg), flush=True)


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
            properties = torch.cuda.get_device_properties(0)
            total_memory = getattr(properties, "total_memory", None)
            if total_memory is None:
                total_memory = getattr(properties, "total_mem")
            print(f"[OK] GPU: {torch.cuda.get_device_name(0)} "
                  f"(VRAM: {total_memory / 1e9:.1f} GB)")
        else:
            print("[WARN] No GPU detected. Training will be very slow on CPU. "
                  "Consider a smaller model like Qwen2.5-0.5B.")
    except ImportError:
        print("[FATAL] PyTorch not installed. Run: pip install torch>=2.6.0")
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


def tokenize_function(examples, tokenizer, max_length: int, prompt_format="instruction", progress=None):
    """Tokenize instruction-output pairs and optionally report completed records."""
    if prompt_format == "native_tools":
        return tokenize_tool_messages(examples, tokenizer, max_length, progress)
    prompts = [
        instruction if prompt_format == "raw" else f"### Instruction:\n{instruction}\n\n### Response:\n"
        for instruction in examples["instruction"]
    ]
    texts = [prompt + output + tokenizer.eos_token for prompt, output in zip(prompts, examples["output"])]
    lengths = tokenizer(texts, truncation=False, padding=False)["input_ids"]
    if any(len(ids) > max_length for ids in lengths):
        raise ValueError("Training example exceeds max sequence length; refusing to truncate its target")
    tokenized = tokenizer(
        texts,
        truncation=True,
        padding="max_length",
        max_length=max_length,
        return_tensors=None,
    )
    prompt_token_ids = tokenizer(
        prompts,
        truncation=True,
        padding=False,
        max_length=max_length,
        return_tensors=None,
    )["input_ids"]
    tokenized["labels"] = [
        [
            token if mask and index >= len(prompt_ids) else -100
            for index, (token, mask) in enumerate(zip(ids, attention))
        ]
        for ids, attention, prompt_ids in zip(
            tokenized["input_ids"], tokenized["attention_mask"], prompt_token_ids
        )
    ]
    if progress:
        progress["completed"] += len(examples["instruction"])
        emit_progress("prepare", prepare_step=progress["completed"], prepare_total=progress["total"])
    return tokenized


def tokenize_tool_messages(examples, tokenizer, max_length, progress=None):
    """Use the model's native chat/tool template; supervise assistant turns only."""
    result = {"input_ids": [], "attention_mask": [], "labels": []}
    for messages, tools in zip(examples["messages"], examples["tools"], strict=True):
        if not messages or messages[-1]["role"] != "assistant":
            raise ValueError("Tool training records must end with an assistant turn")
        rendered = tokenizer.apply_chat_template(messages, tools=tools, tokenize=False,
                                                 add_generation_prompt=False)
        ids = tokenizer.encode(rendered, add_special_tokens=False)
        if len(ids) > max_length:
            raise ValueError("Tool conversation exceeds sequence length; refusing target truncation")
        labels = [-100] * len(ids)
        for index, message in enumerate(messages):
            if message["role"] != "assistant":
                continue
            prefix = tokenizer.apply_chat_template(messages[:index], tools=tools, tokenize=False,
                                                   add_generation_prompt=True)
            through = tokenizer.apply_chat_template(messages[:index + 1], tools=tools, tokenize=False,
                                                    add_generation_prompt=False)
            begin = tokenizer.encode(prefix, add_special_tokens=False)
            end = tokenizer.encode(through, add_special_tokens=False)
            if ids[:len(begin)] != begin or ids[:len(end)] != end:
                raise ValueError("Chat template does not preserve assistant token boundaries")
            labels[len(begin):len(end)] = ids[len(begin):len(end)]
        if not any(label != -100 for label in labels):
            raise ValueError("No assistant training target")
        result["input_ids"].append(ids)
        result["attention_mask"].append([1] * len(ids))
        result["labels"].append(labels)
        if progress:
            progress["completed"] += 1
            emit_progress("prepare", prepare_step=progress["completed"], prepare_total=progress["total"])
    return result


def select_supervised_logits(batch):
    """Project only positions whose next tokens contribute to causal loss."""
    import torch

    shifted = torch.nn.functional.pad(batch["labels"], (0, 1), value=-100)[:, 1:]
    positions = (shifted != -100).any(dim=0).nonzero(as_tuple=True)[0]
    if positions.numel() == 0:
        raise ValueError("No next-token training target")
    return {**batch, "logits_to_keep": positions,
            "shift_labels": shifted.index_select(1, positions).contiguous()}


class NativeToolCollator:
    """Keep full context while avoiding vocabulary logits for masked prompt tokens."""

    def __init__(self, tokenizer):
        from transformers import DataCollatorForSeq2Seq
        self.collator = DataCollatorForSeq2Seq(
            tokenizer, label_pad_token_id=-100, pad_to_multiple_of=8)

    def __call__(self, features):
        sample_ids = [feature.get("_veto_sample_id") for feature in features]
        features = [{key: value for key, value in feature.items() if key != "_veto_sample_id"}
                    for feature in features]
        batch = select_supervised_logits(self.collator(features))
        batch["_veto_diagnostics"] = {
            "sample_ids": sample_ids,
            "input_tokens": int(batch["input_ids"].shape[-1]),
            "supervised_tokens": int((batch["labels"] != -100).sum()),
        }
        return batch


def train(args):
    """Main training loop."""
    started = time.monotonic()
    out_dir = setup_environment(args)

    import torch
    from transformers import (
        AutoModelForCausalLM, AutoTokenizer, TrainingArguments,
        BitsAndBytesConfig, EarlyStoppingCallback
    )
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

    # ── Structured progress callback ──
    from transformers import TrainerCallback

    emit_structured_output = args.structured_output

    class StructuredProgressCallback(TrainerCallback):
        """Emits structured JSON progress lines during training (for offline maintainer logs)."""
        def on_log(self, training_args, state, control, logs=None, **kwargs):
            if logs and emit_structured_output:
                emit_progress("progress",
                              epoch=state.epoch or 0,
                              step=state.global_step,
                              max_steps=state.max_steps,
                              loss=logs.get("loss"),
                              eval_loss=logs.get("eval_loss"),
                              learning_rate=logs.get("learning_rate", None))

        def on_epoch_begin(self, training_args, state, control, **kwargs):
            if emit_structured_output:
                emit_progress("epoch_start", epoch=int(state.epoch or 0) + 1,
                              max_steps=state.max_steps,
                              batch_size=args.batch_size,
                              accumulation_steps=args.gradient_accumulation_steps,
                              effective_batch=args.batch_size * args.gradient_accumulation_steps,
                              learning_rate=training_args.learning_rate)

        def on_step_begin(self, training_args, state, control, **kwargs):
            if emit_structured_output:
                emit_progress("step_start", step=state.global_step + 1, max_steps=state.max_steps)

        def on_step_end(self, training_args, state, control, **kwargs):
            if emit_structured_output:
                emit_progress("step_complete", step=state.global_step, max_steps=state.max_steps)

        def on_epoch_end(self, training_args, state, control, **kwargs):
            if emit_structured_output:
                emit_progress("epoch_end", epoch=int(state.epoch or 0))

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
        trust_remote_code=False,
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
        trust_remote_code=False,
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
        # QLoRA's all-linear setup adapts both attention and MLP projections. Restricting the
        # adapter to attention made the shared JSON tokens easy to learn while the two actual
        # class labels remained poorly separated on scenario-disjoint evaluation data.
        target_modules="all-linear",
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()
    if args.prompt_format == "native_tools":
        import inspect
        if "logits_to_keep" not in inspect.signature(model.get_base_model().forward).parameters:
            raise ValueError("Native tool training requires a model with logits_to_keep support")

    from training_state import CheckpointControl, ResumableTrainer, StepStallWatchdog, prepare_run
    signature, resume_checkpoint = prepare_run(args)
    checkpoint_control = CheckpointControl(out_dir, signature, args.checkpoint_steps)

    # ── Load dataset ──
    eval_path = Path(args.eval_data_path) if args.eval_data_path else None
    datasets = load_dataset(Path(args.data_path), eval_path)

    progress = {"completed": 0, "total": sum(len(split) for split in datasets.values())}
    emit_progress("prepare", prepare_step=0, prepare_total=progress["total"])

    def tokenize_rows(examples, indices):
        result = tokenize_function(examples, tokenizer, args.max_seq_length, args.prompt_format, progress)
        result["_veto_sample_id"] = indices
        return result

    # IDs are frozen split row offsets, never record text or arbitrary user identifiers.
    # Metadata may differ between splits; remove each split's own columns.
    tokenized_datasets = type(datasets)({
        name: split.map(
            tokenize_rows, with_indices=True,
            batched=True, batch_size=100, remove_columns=split.column_names)
        for name, split in datasets.items()
    })

    # ── Training arguments ──
    run_name = f"veto-slm-{int(time.time())}"
    training_args = TrainingArguments(
        output_dir=str(out_dir),
        run_name=run_name,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.eval_batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        gradient_checkpointing=True,
        learning_rate=args.lr,
        weight_decay=0.01,
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        logging_steps=5,
        save_strategy="epoch",
        eval_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        restore_callback_states_from_checkpoint=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8,
        fp16=not (torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8),
        report_to="none",
        # Records are already tokenized; Windows spawn would duplicate dataset/runtime memory.
        dataloader_num_workers=0,
        remove_unused_columns=False,
        prediction_loss_only=True,
        eval_accumulation_steps=1,
    )

    # ── Trainer ──
    trainer = ResumableTrainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_datasets["train"],
        eval_dataset=tokenized_datasets.get("eval"),
        processing_class=tokenizer,
        data_collator=(NativeToolCollator(tokenizer)
                       if args.prompt_format == "native_tools" else None),
        callbacks=[checkpoint_control, StepStallWatchdog(getattr(args, "runtime_monitor", None))] + (
            ([EarlyStoppingCallback(early_stopping_patience=args.early_stopping_patience)]
             if args.early_stopping_patience > 0 else [])
            + ([StructuredProgressCallback()] if args.structured_output else [])
            if tokenized_datasets.get("eval") is not None
            else ([StructuredProgressCallback()] if args.structured_output else [])
        ),
    )

    # ── Train ──
    print(f"\n{'='*60}")
    print(f"Starting training: {args.base_model}")
    print(f"  LoRA rank={args.lora_r}, alpha={args.lora_alpha}")
    print(f"  Epochs={args.epochs}, lr={args.lr}, batch={args.batch_size}")
    print(f"  Output: {out_dir}")
    print(f"{'='*60}\n")

    emit_progress("phase", phase="training", message="Starting QLoRA fine-tuning...")
    training_started = time.monotonic()
    trainer.runtime_monitor = getattr(args, "runtime_monitor", None)
    train_result = trainer.train(
        resume_from_checkpoint=str(resume_checkpoint) if resume_checkpoint else None)
    training_seconds = time.monotonic() - training_started
    if checkpoint_control.paused_checkpoint is not None:
        emit_progress("paused", checkpoint=str(checkpoint_control.paused_checkpoint))
        return None
    emit_progress("phase_complete", phase="training", message="Training complete")

    # ── Save ──
    model.save_pretrained(str(out_dir / "lora-adapter"))
    tokenizer.save_pretrained(str(out_dir / "lora-adapter"))
    print(f"[OK] LoRA adapter saved to {out_dir / 'lora-adapter'}")

    # Merge from a fresh unquantized base. Merging directly into the 4-bit training instance can
    # round away the learned adapter and produce a deployable-looking model that behaves exactly
    # like the untouched base model.
    merge_started = time.monotonic()
    print("Merging LoRA adapter into a fresh unquantized base model ...")
    import gc
    from peft import PeftModel

    del trainer
    del model
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    clean_base = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        device_map="cpu",
        trust_remote_code=False,
        token=args.hf_token,
        torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
    )
    adapter_model = PeftModel.from_pretrained(clean_base, str(out_dir / "lora-adapter"))
    merged = adapter_model.merge_and_unload(safe_merge=True)
    merged.save_pretrained(str(out_dir / "merged"), safe_serialization=True)
    tokenizer.save_pretrained(str(out_dir / "merged"))
    print(f"[OK] Merged model saved to {out_dir / 'merged'}")

    # ── Log summary ──
    log_path = Path(args.log_file)
    log_entry = {
        "base_model": args.base_model,
        "output_dir": relative_log_path(out_dir, log_path),
        "epochs": args.epochs,
        "learning_rate": args.lr,
        "lora_r": args.lora_r,
        "lora_alpha": args.lora_alpha,
        "batch_size": args.batch_size,
        "gradient_accumulation_steps": args.gradient_accumulation_steps,
        "early_stopping_patience": args.early_stopping_patience,
        "train_samples": len(datasets["train"]),
        "eval_samples": len(datasets.get("eval", [])),
        "prompt_format": args.prompt_format,
        "training_seconds": round(training_seconds, 3),
        "merge_seconds": round(time.monotonic() - merge_started, 3),
        "total_seconds": round(time.monotonic() - started, 3),
        "trainer_metrics": train_result.metrics,
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "status": "completed",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    log_path.parent.mkdir(parents=True, exist_ok=True)
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
    from training_state import worker_lock
    arguments = parse_args()
    from runtime_monitor import RuntimeMonitor
    import threading
    evidence_lock = threading.Lock()
    evidence_path = Path(arguments.output_dir) / "runtime-diagnostics.jsonl"

    def record_diagnostic(record):
        line = json.dumps(record, ensure_ascii=True)
        with evidence_lock:
            # Bound evidence storage; never silently pretend monitoring succeeded.
            if evidence_path.exists() and evidence_path.stat().st_size > 16_000_000:
                print(json.dumps({"type": "runtime_diagnostic", "kind": "monitor_incomplete",
                                  "message": "Evidence file limit reached", "timestamp": time.time()}), flush=True)
            else:
                with evidence_path.open("a", encoding="utf-8") as stream:
                    stream.write(line + "\n")
                    stream.flush()
            print(line, flush=True)

    with worker_lock(arguments.output_dir):
        monitor = RuntimeMonitor(Path(arguments.output_dir).name, record_diagnostic,
                                 float(os.environ.get("VETO_SLOW_STEP_SECONDS", "120")),
                                 float(os.environ.get("VETO_NO_PROGRESS_SECONDS", "600")))
        arguments.runtime_monitor = monitor
        monitor.start()
        try:
            train(arguments)
        except BaseException as error:
            monitor.exception(error)
            raise
        finally:
            monitor.close()
