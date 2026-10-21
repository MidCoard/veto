"""Export a fresh GGUF artifact with llama.cpp; never install or overwrite a model."""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from runtime_paths import TRAINING_WORK, configure_caches

configure_caches()


def relative_log_path(target: str | Path, log_file: str | Path) -> str:
    target_path = Path(target)
    try:
        return Path(os.path.relpath(target_path.resolve(), Path(log_file).resolve().parent)).as_posix()
    except ValueError:
        return target_path.name


def quantizer(llama_dir: Path) -> Path:
    for relative in ("llama-quantize.exe", "llama-quantize", "build/bin/llama-quantize",
                     "build/bin/llama-quantize.exe", "build/bin/Release/llama-quantize.exe"):
        candidate = llama_dir / relative
        if candidate.is_file():
            return candidate
    installed = shutil.which("llama-quantize")
    if installed:
        return Path(installed)
    raise FileNotFoundError("llama-quantize is required; f16 is not a Q4 substitute")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--llama-cpp-dir", type=Path, required=True)
    parser.add_argument("--model-name", required=True)
    parser.add_argument("--quantize-type", choices=["q2_k", "q3_k_m", "q4_k_m", "q5_k_m", "q8_0", "f16"], default="q4_k_m")
    parser.add_argument("--log-file", type=Path, default=TRAINING_WORK / "conversion.jsonl")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]*", args.model_name):
        raise ValueError("model-name must be a plain artifact name")
    if not args.model_dir.is_dir():
        raise FileNotFoundError(args.model_dir)
    converter = args.llama_cpp_dir / "convert_hf_to_gguf.py"
    if not converter.is_file():
        raise FileNotFoundError(converter)
    executable = quantizer(args.llama_cpp_dir) if args.quantize_type != "f16" else None
    f16 = args.output_dir / f"{args.model_name}-f16.gguf"
    final = args.output_dir / f"{args.model_name}-{args.quantize_type}.gguf"
    if f16.exists() or final.exists():
        raise FileExistsError("Use a fresh conversion directory; existing models are never reused")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(converter), str(args.model_dir), "--outfile", str(f16), "--outtype", "f16"], check=True)
    if not f16.is_file() or not f16.stat().st_size:
        raise RuntimeError("Converter did not produce a nonempty GGUF")
    if executable is not None:
        subprocess.run([str(executable), str(f16), str(final), args.quantize_type], check=True)
    if not final.is_file() or not final.stat().st_size:
        raise RuntimeError("Quantizer did not produce a nonempty GGUF")
    args.log_file.parent.mkdir(parents=True, exist_ok=True)
    report = {"gguf_path": relative_log_path(final, args.log_file), "quantize_type": args.quantize_type, "status": "completed"}
    with args.log_file.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(report) + "\n")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
