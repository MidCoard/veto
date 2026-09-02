# Project Veto — Model Training Guide

## Overview

This pipeline fine-tunes a lightweight Qwen model (1.5B or 0.5B) for the C7 Local SLM Veto Gateway.
The trained model outputs GBNF-grammar-constrained JSON for veto decisions, redaction, and structural
constraint enforcement.

The pipeline is **bundled with the Java backend** — Java's `TrainingManager` launches Python
subprocesses (same pattern as `LlamaCppBridge` → `llama-server`).

### Features (Part 6: Local Training Framework)

- **6.1 Factory-Trained Base** — Veto ships with pre-specialized SLMs for Gateway screening
- **6.2 Local Fine-Tuning Pipeline** — Optional post-deployment personalization via LoRA/QLoRA
- **6.3 Quality Filter** — Automated quality gate validates training data before fine-tuning

## Prerequisites

| Requirement | Minimum |
|---|---|
| Python | 3.10+ |
| RAM | 16 GB (for 1.5B QLoRA) |
| GPU VRAM | 6 GB (8 GB recommended for 1.5B) |
| Disk | 15 GB free |
| llama.cpp | Optional (for GGUF conversion) |

## Quick Start

### 1. Generate & Validate Training Data

```bash
cd D:\ideaProjects\Veto\training\python

# Generate data and run quality filter (default)
python prepare_data.py

# Generate data without quality check (for development)
python prepare_data.py --skip-quality-check
```

Creates:
- `training/data/veto_training_data.jsonl` — 400+ training examples
- `training/data/veto_eval_data.jsonl` — 100+ evaluation examples
- `training/data/quality_report.json` — Quality filter report (Feature 6.3)

### 2. Run Quality Filter Standalone (Feature 6.3)

```bash
# Validate existing training data
python quality_filter.py --data ../data/veto_training_data.jsonl

# With custom output path and fail-on-invalid
python quality_filter.py --data ../data/veto_training_data.jsonl \
    --output ../data/veto_training_data_filtered.jsonl \
    --fail-on-invalid
```

The quality filter checks:
- JSONL format validity (each line is valid JSON with required fields: id, task, instruction, output)
- Output JSON schema conformance (veto_decision ∈ {pass, redact, block}, secrets_found is boolean, etc.)
- Instruction length bounds (min 10 chars, max 4096 chars)
- Deduplication by (instruction, output) pair

### 3. Install Python Dependencies

```bash
# Option A: Global install
pip install -r requirements.txt

# Option B: Virtual environment (recommended)
python -m venv ../.venv
../.venv\Scripts\pip install -r requirements.txt
```

### 4. Fine-Tune (QLoRA)

```bash
# Standard training
python train.py --base-model Qwen/Qwen2.5-1.5B-Instruct \
    --data-path ../data/veto_training_data.jsonl \
    --output-dir ../models/fine-tuned \
    --epochs 3 \
    --batch-size 4

# With quality filter enabled (runs filter before training)
python train.py --base-model Qwen/Qwen2.5-1.5B-Instruct \
    --data-path ../data/veto_training_data.jsonl \
    --output-dir ../models/fine-tuned \
    --quality-filter

# Lower memory (8 GB VRAM)
python train.py --base-model Qwen/Qwen2.5-0.5B-Instruct \
    --data-path ../data/veto_training_data.jsonl \
    --output-dir ../models/fine-tuned \
    --batch-size 2 \
    --gradient-accumulation-steps 4
```

**Structured Progress Output:** The training script emits structured JSON progress lines to stdout
for the Java `TrainingManager` to parse:

```json
{"type":"phase","phase":"training","message":"Starting QLoRA fine-tuning...","timestamp":...}
{"type":"progress","epoch":1,"step":50,"max_steps":200,"loss":0.45,"timestamp":...}
{"type":"epoch_start","epoch":2,"timestamp":...}
{"type":"epoch_end","epoch":2,"timestamp":...}
{"type":"phase_complete","phase":"training","message":"Training complete","timestamp":...}
```

### 5. Convert to GGUF

```bash
# With llama.cpp installed:
python convert_to_gguf.py --model-dir ../models/fine-tuned/merged \
    --quantize-type q4_k_m

# Or set LLAMA_CPP_DIR:
set LLAMA_CPP_DIR=D:\projects\llama.cpp
python convert_to_gguf.py --model-dir ../models/fine-tuned/merged
```

Outputs:
- `training/models/veto-slm-q4_k_m.gguf`
- `training/models/veto-slm.gguf` (copied to project model path)

### 6. Evaluate

```bash
python evaluate.py \
    --model ../models/veto-slm-q4_k_m.gguf \
    --data ../data/veto_eval_data.jsonl \
    --json-output
```

The `--json-output` flag produces a machine-readable report that maps directly to the Java
`TrainingProgress.EvaluationReport` record:

```json
{
  "modelPath": "...",
  "datasetPath": "...",
  "timestamp": "...",
  "totalSamples": 120,
  "elapsedSeconds": 45.2,
  "gbnfCompliance": {"validJsonCount": 114, "validJsonRate": 0.95},
  "decisionAccuracy": {"correct": 106, "total": 120, "accuracy": 0.8833},
  "redactionAccuracy": {"truePositives": 80, "falsePositives": 5, "falseNegatives": 15, "precision": 0.94, "recall": 0.84, "f1": 0.89},
  "structuralValidation": {"correct": 108, "total": 120, "accuracy": 0.90}
}
```

### 7. Deploy via Java API

```bash
# Start training with custom parameters:
curl -X POST http://localhost:8443/api/v1/training/start \
    -H "Content-Type: application/json" \
    -d '{
      "baseModel": "Qwen/Qwen2.5-0.5B-Instruct",
      "epochs": 1,
      "batchSize": 2,
      "loraRank": 8,
      "skipQualityFilter": false
    }'

# Run quality check without training:
curl -X POST http://localhost:8443/api/v1/training/quality-check

# Get training progress:
curl http://localhost:8443/api/v1/training/progress

# Get evaluation report:
curl http://localhost:8443/api/v1/training/evaluation

# Deploy a specific model:
curl -X POST http://localhost:8443/api/v1/training/deploy \
    -H "Content-Type: application/json" \
    -d '{"modelPath": "./training/models/veto-slm-q4_k_m.gguf"}'

# Get training system status:
curl http://localhost:8443/api/v1/training/status
```

## Training Data Format

Each JSONL line:

```json
{
  "id": "veto-train-0001",
  "task": "veto_decision",
  "instruction": "Analyze the following payload and decide if it should pass, be redacted, or blocked:\n...",
  "output": "{\"veto_decision\":\"pass\",\"data\":{\"reason\":\"...\",\"confidence\":0.98}}"
}
```

Three task types:
- **veto_decision**: Pass/redact/block decisions
- **redaction**: Secrets and sensitive field detection
- **structural_constraint**: Physics normalization and structural rule enforcement

## Model Requirements

The final GGUF model must:
1. Be loadable by `llama.cpp` / `llama-server`
2. Output JSON that conforms to `grammars/veto-output.gbnf`
3. Be placed at `./models/veto-slm.gguf` (relative to project root) for auto-discovery
4. Work with `--grammar-file` flag (as configured in `LlamaCppBridge`)

## Architecture

```
Java TrainingManager           Python Scripts
┌─────────────────┐           ┌──────────────────┐
│ startTraining() │──subproc──▶ prepare_data.py  │
│                 │           │  (+ quality check)│
│                 │           └────────┬─────────┘
│                 │           ┌────────▼─────────┐
│                 │──subproc──▶ quality_filter.py │ (Feature 6.3)
│                 │           └────────┬─────────┘
│                 │           ┌────────▼─────────┐
│                 │──subproc──▶ train.py          │
│                 │           │ (structured output)│
│                 │           └────────┬─────────┘
│                 │           ┌────────▼─────────┐
│                 │──subproc──▶ convert_to_gguf.py│
│                 │           └────────┬─────────┘
│                 │           ┌────────▼─────────┐
│                 │──subproc──▶ evaluate.py       │
│                 │           │ (JSON report)     │
│                 │           └────────┬─────────┘
│ deployModel()   │──copies──▶ models/veto-slm.gguf
│                 │              │
│ restartBridge() │──calls──▶ LlamaCppBridge.stop()
│                 │           LlamaCppBridge.start()
│                 │           (now uses HTTP API)
└─────────────────┘
```

## Configuration (application.yml)

```yaml
veto:
  training:
    python-path: python
    training-dir: ./training
    model-output-dir: ./models
    base-model: Qwen/Qwen2.5-1.5B-Instruct
    default-gguf-name: veto-slm.gguf
    max-training-hours: 4
    auto-deploy-on-completion: true
    venv-path: ./training/.venv
    restart-bridge-on-deploy: true
    quality-filter-enabled: true       # Feature 6.3 quality gate
```

## Troubleshooting

| Problem | Solution |
|---|---|
| CUDA out of memory | Reduce batch size, use 0.5B model, or enable gradient checkpointing |
| llama-server not starting | Ensure the GGUF model is at the configured path; check `VETO_LLAMA_MODEL_PATH` |
| GBNF grammar not found | Copy `training/grammars/veto-output.gbnf` to `./grammars/veto-output.gbnf` |
| Python module not found | Activate the venv: `training/.venv/Scripts/activate` |
| GGUF conversion fails | Set `LLAMA_CPP_DIR` to your llama.cpp checkout path |
| Quality filter fails | Check `quality_report.json` for invalid record details; fix or remove bad data |
| Training progress not updating | Ensure `train.py --structured-output` is used (default since v2) |
