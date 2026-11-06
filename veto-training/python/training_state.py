"""Complete, input-bound checkpoints and cooperative optimizer-boundary pauses."""
import hashlib
import importlib.metadata
import json
import math
import os
import platform
from contextlib import contextmanager
from pathlib import Path

import torch
from torch.utils.data import Sampler
from transformers import Trainer, TrainerCallback


@contextmanager
def worker_lock(output):
    """Keep the writer locked even if its Java parent exits unexpectedly."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with (output / "worker.lock").open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            lock = lambda: msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            unlock = lambda: msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            lock = lambda: fcntl.lockf(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
            unlock = lambda: fcntl.lockf(stream.fileno(), fcntl.LOCK_UN, 1)
        try:
            lock()
        except OSError as error:
            raise RuntimeError("Another training worker owns this run") from error
        try:
            yield
        finally:
            stream.seek(0)
            unlock()


class EpochSampler(Sampler):
    """Derive shuffling from Trainer's restored epoch, including after batch skipping."""
    def __init__(self, size, seed, epoch):
        self.size = size
        self.seed = seed
        self.epoch = epoch

    def __len__(self):
        return self.size

    def __iter__(self):
        generator = torch.Generator().manual_seed(self.seed + self.epoch())
        return iter(torch.randperm(self.size, generator=generator).tolist())


class ResumableTrainer(Trainer):
    def _get_train_sampler(self, train_dataset=None):
        dataset = self.train_dataset if train_dataset is None else train_dataset
        if dataset is None:
            raise ValueError("Training dataset is required")
        seed = self.args.data_seed if self.args.data_seed is not None else self.args.seed
        return EpochSampler(len(dataset), seed, lambda: int(self.state.epoch or 0))


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def atomic_text(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def run_signature(args):
    from transformers.utils.hub import cached_file

    model_config = cached_file(args.base_model, "config.json", token=args.hf_token)
    if model_config is None:
        raise ValueError("Cannot identify the base model")
    model_directory = Path(model_config).parent
    model_files = {str(p.relative_to(model_directory)): digest(p)
                   for p in sorted(model_directory.rglob("*"))
                   if p.is_file() and p.suffix in (".json", ".safetensors", ".bin", ".txt", ".model")}
    return {
        "output_directory": str(Path(args.output_dir).resolve()),
        "base_model": args.base_model, "model_files": model_files,
        "python": platform.python_version(), "cuda_devices": torch.cuda.device_count(),
        "bf16": torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8,
        "training_data": digest(args.data_path), "validation_data": digest(args.eval_data_path)
        if args.eval_data_path else None,
        "options": {name: getattr(args, name) for name in (
            "epochs", "lr", "batch_size", "eval_batch_size", "gradient_accumulation_steps",
            "lora_r", "lora_alpha", "lora_dropout", "max_seq_length", "prompt_format", "checkpoint_steps")},
        "code": {name: digest(Path(__file__).with_name(name))
                 for name in ("train.py", "training_state.py")},
        "versions": {name: importlib.metadata.version(name)
                     for name in ("torch", "transformers", "peft", "datasets", "bitsandbytes", "accelerate")},
    }


def checkpoint_files(checkpoint):
    paths = sorted(Path(checkpoint).rglob("*"))
    if any(path.is_symlink() for path in paths):
        raise ValueError("Checkpoint files must not be links")
    return {str(path.relative_to(checkpoint)): digest(path)
            for path in paths if path.is_file() and path.name not in ("complete.json", "complete.json.tmp")}


def complete_checkpoint(checkpoint, signature, optimization_finished=False):
    checkpoint = Path(checkpoint)
    required = ("adapter_model.safetensors", "adapter_config.json", "optimizer.pt",
                "scheduler.pt", "rng_state.pth", "trainer_state.json")
    if not all((checkpoint / name).is_file() for name in required):
        raise ValueError("Checkpoint is missing training state")
    state = json.loads((checkpoint / "trainer_state.json").read_text(encoding="utf-8"))
    stopping = state.get("stateful_callbacks", {}).get("EarlyStoppingCallback", {})
    if stopping:
        optimization_finished |= (stopping["attributes"]["early_stopping_patience_counter"]
                                  >= stopping["args"]["early_stopping_patience"])
    atomic_text(checkpoint / "complete.json", json.dumps(
        {"signature": signature, "files": checkpoint_files(checkpoint),
         "optimization_finished": optimization_finished}, sort_keys=True))
    return optimization_finished


def validate_checkpoint(output, checkpoint, signature):
    checkpoint = Path(checkpoint)
    if checkpoint.is_symlink():
        raise ValueError("Checkpoint must not be a link")
    checkpoint = checkpoint.resolve(strict=True)
    if checkpoint.parent != Path(output).resolve() or not checkpoint.name.startswith("checkpoint-"):
        raise ValueError("Checkpoint does not belong to this run")
    marker = checkpoint / "complete.json"
    if not marker.is_file():
        raise ValueError("Checkpoint has no completion marker")
    saved = json.loads(marker.read_text(encoding="utf-8"))
    if saved["signature"] != signature:
        raise ValueError("Checkpoint model, data, configuration or runtime does not match")
    if saved["optimization_finished"]:
        raise ValueError("Checkpoint already completed optimization; export recovery is separate")
    if saved["files"] != checkpoint_files(checkpoint):
        raise ValueError("Checkpoint files are incomplete or modified")
    state = json.loads((checkpoint / "trainer_state.json").read_text(encoding="utf-8"))
    if checkpoint.name != f"checkpoint-{state['global_step']}":
        raise ValueError("Checkpoint step does not match directory")
    if state["global_step"] <= 0 or state["global_step"] >= state["max_steps"]:
        raise ValueError("Checkpoint already completed optimization; it cannot resume mid-run")
    return checkpoint


def prepare_run(args):
    if args.checkpoint_steps <= 0:
        raise ValueError("checkpoint-steps must be positive")
    output = Path(args.output_dir)
    signature = run_signature(args)
    manifest = output / "run.json"
    if args.resume_from_checkpoint:
        if not manifest.is_file() or json.loads(manifest.read_text(encoding="utf-8")) != signature:
            raise ValueError("Run model, data, configuration or runtime does not match")
        checkpoint = validate_checkpoint(output, args.resume_from_checkpoint, signature)
        (output / "paused.checkpoint").unlink(missing_ok=True)
    else:
        if manifest.exists():
            raise ValueError("Run already exists; explicitly resume a complete checkpoint")
        checkpoint = None
        atomic_text(manifest, json.dumps(signature, sort_keys=True))
    return signature, checkpoint


class CheckpointControl(TrainerCallback):
    def __init__(self, output, signature, interval):
        self.output = Path(output)
        self.signature = signature
        self.interval = interval
        self.pause_requested = False
        self.paused_checkpoint = None

    def on_step_end(self, args, state, control, **kwargs):
        epoch = state.epoch or 0.0
        boundary = epoch > 0 and math.isclose(epoch, round(epoch), abs_tol=1e-8)
        if boundary:
            # Epoch validation updates best-model/early-stopping state before this save is complete.
            control.should_save = False
        elif state.global_step % self.interval == 0:
            control.should_save = True
        # Once optimization is complete, its normal completion/export result wins the race.
        if state.global_step < state.max_steps and (self.output / "pause.request").exists():
            self.pause_requested = True
            control.should_save = not boundary
            control.should_training_stop = True
        return control

    def on_save(self, args, state, control, **kwargs):
        checkpoint = self.output / f"checkpoint-{state.global_step}"
        finished = complete_checkpoint(checkpoint, self.signature,
                                       optimization_finished=control.should_training_stop and not self.pause_requested)
        if self.pause_requested and not finished:
            self.paused_checkpoint = checkpoint
            atomic_text(self.output / "paused.checkpoint", checkpoint.name + "\n")
        if self.pause_requested:
            (self.output / "pause.request").unlink(missing_ok=True)
        return control

    def on_epoch_end(self, args, state, control, **kwargs):
        epoch = state.epoch or 0.0
        if self.pause_requested and not math.isclose(epoch, round(epoch), abs_tol=1e-8):
            # A cooperative stop must not introduce an extra validation/early-stopping event.
            control.should_evaluate = False
            control.should_save = False
        return control
