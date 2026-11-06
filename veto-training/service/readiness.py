"""Bounded, CPU-only recipe checks. Does not load or train models."""
import json
from pathlib import Path
from profiles import DEFAULTS, validate
from assets import record_count


def check(root, config, values, purpose):
    issues, counts = [], {}
    try:
        _, settings = validate(root, "check", {k: values.get(k, v) for k, v in DEFAULTS.items()})
    except ValueError as error:
        return {"ready": False, "issues": [str(error)], "counts": counts}
    counts = {key: record_count(Path(settings[key])) for key in ("training.data", "validation.data")}
    model = Path(settings["base.model"])
    if not (model / "tokenizer.json").is_file():
        issues.append("模型缺少 tokenizer.json。")
    if not any(model.glob("*.safetensors")) and not (model / "pytorch_model.bin").is_file():
        issues.append("模型目录缺少权重文件。")
    for key, label in (("java", "Java"), ("trainingJar", "训练程序")):
        if not (root / config[key]).is_file():
            issues.append(label + "路径不可用。")
    if not (root / values.get("python", "")).is_file():
        issues.append("训练 Python 路径不可用。")
    converter = root / values.get("llama.cpp", "")
    if not (converter / "convert_hf_to_gguf.py").is_file():
        issues.append("GGUF 转换程序不可用。")
    import shutil
    candidates = [converter / name for name in ("llama-quantize.exe", "llama-quantize", "build/bin/Release/llama-quantize.exe", "build/bin/llama-quantize.exe", "build/bin/llama-quantize")]
    if not any(p.is_file() for p in candidates) and not shutil.which("llama-quantize", path=config.get("environment", {}).get("PATH")):
        issues.append("GGUF 量化程序不可用。")
    if purpose == "screening":
        if settings["prompt.format"] != "instruction":
            issues.append("Core 意图识别需要指令与回答格式。")
        seen, groups = [], []
        for key, label in (("training.data", "训练集"), ("validation.data", "验证集")):
            path = Path(settings[key])
            if path.stat().st_size > 20_000_000:
                issues.append(label + "超过本次页面检查的 20 MB 上限。")
                continue
            hashes, scenario_groups = set(), set()
            count = 0
            try:
                import hashlib
                with path.open(encoding="utf-8") as stream:
                    for line in stream:
                        if not line.strip():
                            continue
                        record = json.loads(line)
                        target = json.loads(record["output"])
                        if not isinstance(record["instruction"], str) or not record["instruction"].strip() or target.get("relevance") not in ("HIGH", "MEDIUM", "LOW") or target.get("danger") not in ("SAFE", "ELEVATED", "DANGEROUS", "CRITICAL") or not isinstance(target.get("reason"), str):
                            raise ValueError()
                        hashes.add(hashlib.sha256(record["instruction"].strip().encode()).hexdigest())
                        if record.get("scenarioGroup"):
                            scenario_groups.add(record["scenarioGroup"])
                        count += 1
                if not count:
                    raise ValueError()
                counts[key] = count
            except (ValueError, KeyError, TypeError, AttributeError):
                issues.append(label + "存在无效的 Core 标签或记录。")
            seen.append(hashes)
            groups.append(scenario_groups)
        if len(seen) == 2 and (seen[0] & seen[1] or groups[0] & groups[1]):
            issues.append("训练集与验证集存在重复输入或场景分组。")
    return {"ready": not issues, "issues": issues, "counts": counts}
