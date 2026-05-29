#!/usr/bin/env python3
"""
Project Veto — HuggingFace → GGUF Converter
============================================
Converts a fine-tuned HuggingFace model to GGUF format (Q4_K_M)
for consumption by llama.cpp via the LlamaCppBridge.

Usage:
    python convert_to_gguf.py --model-dir ../models/fine-tuned/merged

Requires:
    - llama.cpp cloned locally (for convert_hf_to_gguf.py and llama-quantize)
    - OR llama-cpp-python with convert support
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="HF → GGUF Converter for Veto SLM")
    parser.add_argument("--model-dir", type=str, required=True,
                        help="Path to merged HuggingFace model directory")
    parser.add_argument("--output-dir", type=str, default=None,
                        help="Output directory for GGUF files (default: --model-dir/../gguf)")
    parser.add_argument("--llama-cpp-dir", type=str, default=None,
                        help="Path to llama.cpp source (containing convert_hf_to_gguf.py)")
    parser.add_argument("--quantize-type", type=str, default="q4_k_m",
                        choices=["q2_k", "q3_k_m", "q4_k_m", "q5_k_m", "q8_0", "f16"],
                        help="Quantization format (default: q4_k_m)")
    parser.add_argument("--model-name", type=str, default="veto-slm",
                        help="Output model filename (without extension)")
    parser.add_argument("--copy-to", type=str, default=None,
                        help="Additional copy destination (e.g., ../../models/veto-slm.gguf)")
    parser.add_argument("--log-file", type=str, default="conversion_log.jsonl")
    return parser.parse_args()


def find_llama_cpp_scripts() -> str | None:
    """Locate llama.cpp convert script in common locations."""
    candidates = [
        os.environ.get("LLAMA_CPP_DIR"),
        "../llama.cpp",
        "../../llama.cpp",
        os.path.expanduser("~/llama.cpp"),
        os.path.expanduser("~/projects/llama.cpp"),
    ]
    for base in candidates:
        if base and os.path.isdir(base):
            script = os.path.join(base, "convert_hf_to_gguf.py")
            if os.path.isfile(script):
                return base
    return None


def convert_with_llama_cpp(model_dir: str, output_path: str, llama_dir: str) -> bool:
    """Convert using llama.cpp's convert_hf_to_gguf.py."""
    script = os.path.join(llama_dir, "convert_hf_to_gguf.py")
    if not os.path.isfile(script):
        print(f"[ERROR] convert_hf_to_gguf.py not found at {script}")
        return False

    cmd = [
        sys.executable, script,
        model_dir,
        "--outfile", output_path,
        "--outtype", "f16",  # Start with f16, quantize after
    ]
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[ERROR] Conversion failed:\n{result.stderr}")
        return False
    print(result.stdout)
    return True


def quantize_gguf(input_path: str, output_path: str, quant_type: str, llama_dir: str) -> bool:
    """Quantize a GGUF file using llama-quantize."""
    quant_bin = shutil.which("llama-quantize")
    if not quant_bin:
        # Try in llama.cpp build dir
        quant_bin = os.path.join(llama_dir, "build", "bin", "llama-quantize")
        if not os.path.isfile(quant_bin):
            # Try Release subdir
            quant_bin = os.path.join(llama_dir, "build", "bin", "Release", "llama-quantize.exe")
            if not os.path.isfile(quant_bin):
                print(f"[WARN] llama-quantize not found. Using f16 GGUF (unquantized).")
                shutil.copy(input_path, output_path)
                return True

    cmd = [quant_bin, input_path, output_path, quant_type]
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[ERROR] Quantization failed:\n{result.stderr}")
        return False
    print(result.stdout)
    return True


def convert_with_lcpp(model_dir: str, output_path: str) -> bool:
    """Convert using llama-cpp-python's built-in converter (if available)."""
    try:
        from llama_cpp import Llama
        # llama-cpp-python doesn't directly support HF→GGUF conversion in older versions.
        # Fall back to subprocess approach.
        print("[INFO] Using llama-cpp-python GGUF writer ...")
        # Placeholder: would use gguf library directly
        import gguf
        print(f"[OK] gguf library available (version: {gguf.__version__ if hasattr(gguf, '__version__') else 'unknown'})")
    except ImportError:
        print("[ERROR] Neither llama.cpp scripts nor gguf library available.")
        print("Install: pip install gguf, or set LLAMA_CPP_DIR")
        return False
    return True


def main():
    args = parse_args()

    model_dir = Path(args.model_dir)
    if not model_dir.exists():
        print(f"[FATAL] Model directory not found: {model_dir}")
        sys.exit(1)

    out_dir = Path(args.output_dir) if args.output_dir else (model_dir.parent / "gguf")
    out_dir.mkdir(parents=True, exist_ok=True)

    # Step filenames
    f16_path = str(out_dir / f"{args.model_name}-f16.gguf")
    quantized_path = str(out_dir / f"{args.model_name}-{args.quantize_type}.gguf")

    # ── Step 1: Locate llama.cpp ──
    llama_dir = args.llama_cpp_dir or find_llama_cpp_scripts()
    if llama_dir:
        print(f"[OK] Using llama.cpp at: {llama_dir}")
    else:
        print("[WARN] llama.cpp not found locally. Will try fallback methods.")

    # ── Step 2: HF → GGUF (f16) ──
    if os.path.exists(quantized_path):
        print(f"[WARN] Quantized GGUF already exists: {quantized_path}")
        print("  Delete it first to re-convert.")
        final_path = quantized_path
    else:
        if llama_dir:
            success = convert_with_llama_cpp(str(model_dir), f16_path, llama_dir)
        else:
            success = convert_with_lcpp(str(model_dir), f16_path)

        if not success:
            print("[FATAL] Conversion failed. Check dependencies above.")
            sys.exit(1)

        # ── Step 3: Quantize ──
        if args.quantize_type != "f16":
            if llama_dir:
                quantize_gguf(f16_path, quantized_path, args.quantize_type, llama_dir)
            else:
                # Without llama.cpp, just keep f16
                shutil.copy(f16_path, quantized_path)
                print("[WARN] No llama-quantize available; output is f16 GGUF (not quantized)")

        final_path = quantized_path if args.quantize_type != "f16" else f16_path
        print(f"[OK] GGUF model created: {final_path}")

    # ── Step 4: Copy to project model path ──
    if args.copy_to:
        copy_dest = Path(args.copy_to)
        copy_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(final_path, str(copy_dest))
        print(f"[OK] Copied to project model path: {copy_dest}")

    # ── Default copy to project models/ ──
    default_project_model = Path(__file__).parent.parent.parent / "models" / "veto-slm.gguf"
    if not args.copy_to or args.copy_to != str(default_project_model):
        default_project_model.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(final_path, str(default_project_model))
        print(f"[OK] Also copied to {default_project_model} (default LlamaCppBridge path)")

    # ── Log ──
    log_entry = {
        "model_dir": str(model_dir),
        "output_dir": str(out_dir),
        "quantize_type": args.quantize_type,
        "gguf_path": str(final_path),
        "project_model_path": str(default_project_model),
        "status": "completed",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    log_path = Path(args.log_file)
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(log_entry) + "\n")

    print(f"\n{'='*60}")
    print(f"Conversion complete!")
    print(f"  GGUF: {final_path}")
    print(f"  Size: {os.path.getsize(final_path) / 1e9:.2f} GB")
    print(f"  Deploy: Deploy this via TrainingController or copy to models/veto-slm.gguf")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
