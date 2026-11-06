"""Restore a merged model from completed training outputs without optimizing again."""
import argparse
import json
import shutil
from pathlib import Path


def regular(path):
    return path.is_file() and not path.is_symlink()


def complete_merged(root):
    root = Path(root)
    if root.is_symlink() or not regular(root / "config.json"):
        return False
    try:
        json.loads((root / "config.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if any(regular(root / name) for name in ("model.safetensors", "pytorch_model.bin")):
        return True
    for name in ("model.safetensors.index.json", "pytorch_model.bin.index.json"):
        index = root / name
        if not regular(index):
            continue
        try:
            weights = set(json.loads(index.read_text(encoding="utf-8"))["weight_map"].values())
        except (OSError, ValueError, KeyError, TypeError):
            continue
        if weights and all(regular(root / weight) for weight in weights):
            return True
    return False


def adapter_directory(output):
    output = Path(output)
    adapter = output / "lora-adapter"
    if regular(adapter / "adapter_config.json") and any(
            regular(adapter / name) for name in ("adapter_model.safetensors", "adapter_model.bin")):
        return adapter
    checkpoints = sorted(output.glob("checkpoint-*"),
                         key=lambda path: int(path.name.removeprefix("checkpoint-"))
                         if path.name.removeprefix("checkpoint-").isdigit() else -1,
                         reverse=True)
    for checkpoint in checkpoints:
        if checkpoint.is_symlink():
            continue
        marker = checkpoint / "complete.json"
        if not regular(marker):
            continue
        try:
            saved = json.loads(marker.read_text(encoding="utf-8"))
            if not saved.get("optimization_finished"):
                continue
            if not regular(checkpoint / "adapter_config.json") or not any(
                    regular(checkpoint / name) for name in ("adapter_model.safetensors", "adapter_model.bin")):
                continue
            from training_state import checkpoint_files
            if saved["files"] != checkpoint_files(checkpoint):
                raise ValueError("Finished checkpoint files are incomplete or modified")
            return checkpoint
        except (OSError, KeyError, TypeError, json.JSONDecodeError):
            continue
    raise ValueError("No complete merged model, adapter, or optimization-finished checkpoint is available")


def remove_generated(path):
    path = Path(path)
    if path.is_symlink() or path.is_junction():
        raise ValueError("Export directories must not be links")
    if path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()


def merge_adapter(base_model, output, adapter):
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    output = Path(output)
    temporary = output / "merged.exporting"
    remove_generated(temporary)
    tokenizer = AutoTokenizer.from_pretrained(base_model, trust_remote_code=False)
    clean_base = AutoModelForCausalLM.from_pretrained(
        base_model,
        device_map="cpu",
        trust_remote_code=False,
        torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
    )
    adapter_model = PeftModel.from_pretrained(clean_base, str(adapter))
    merged = adapter_model.merge_and_unload(safe_merge=True)
    merged.save_pretrained(str(temporary), safe_serialization=True)
    tokenizer.save_pretrained(str(temporary))
    if not complete_merged(temporary):
        raise ValueError("Merge did not produce a complete model")
    remove_generated(output / "merged")
    temporary.replace(output / "merged")


def ensure_merged(base_model, output):
    output = Path(output)
    merged = output / "merged"
    if not complete_merged(merged):
        merge_adapter(base_model, output, adapter_directory(output))
    print(json.dumps({"type": "phase_complete", "phase": "merge"}), flush=True)
    return merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-model", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    from training_state import worker_lock
    with worker_lock(args.output_dir):
        ensure_merged(args.base_model, args.output_dir)


if __name__ == "__main__":
    main()
