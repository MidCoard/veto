"""Editable training recipes; executables stay host-owned and users choose output locations."""
import json
import math
from pathlib import Path

DEFAULTS = {
    "base.model": "", "training.data": "", "validation.data": "",
    "prompt.format": "native_tools", "epochs": "3", "learning.rate": "0.0001",
    "batch.size": "1", "accumulation.steps": "8", "sequence.length": "16384",
    "early.stopping.patience": "3",
    "lora.rank": "16", "lora.alpha": "32", "lora.dropout": "0.05", "checkpoint.steps": "100",
    "output.directory": "",
}
INTEGERS = {"epochs": (1, 100), "batch.size": (1, 128), "accumulation.steps": (1, 1024),
            "sequence.length": (32, 65536), "lora.rank": (1, 512), "lora.alpha": (1, 1024),
            "early.stopping.patience": (0, 100), "checkpoint.steps": (1, 100000)}


class InvalidProfile(ValueError):
    def __init__(self, field, message):
        super().__init__(message)
        self.field = field


def validate(root, name, values, check_data=True):
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 for c in name):
        raise InvalidProfile("name", "任务名称需要 1–80 个字符。")
    if not isinstance(values, dict) or set(values) != set(DEFAULTS):
        raise InvalidProfile("name", "训练配置字段不完整或包含不支持的字段。")
    result = {}
    for key, value in values.items():
        if not check_data and key in ("training.data", "validation.data"):
            result[key] = value if isinstance(value, str) else ""
            continue
        if not isinstance(value, str) or not value.strip() or len(value) > 1024 or any(ord(c) < 32 for c in value):
            raise InvalidProfile(key, "请填写有效值，不能包含换行或控制字符。")
        result[key] = value.strip()
    for key, (low, high) in INTEGERS.items():
        if not result[key].isascii() or not result[key].isdigit() or not low <= int(result[key]) <= high:
            raise InvalidProfile(key, f"请输入 {low}–{high} 之间的整数。")
    for key, low, high in [("learning.rate", 0, 1), ("lora.dropout", 0, 1)]:
        try:
            value = float(result[key])
        except ValueError:
            value = math.nan
        if not math.isfinite(value) or value < low or value >= high or (key == "learning.rate" and value == 0):
            raise InvalidProfile(key, "学习率需大于 0 且小于 1；Dropout 需在 0（含）到 1（不含）之间。")
    if result["prompt.format"] not in ("native_tools", "instruction", "raw"):
        raise InvalidProfile("prompt.format", "请选择支持的数据格式。")
    for key in ("base.model", "training.data", "validation.data"):
        if not check_data and key != "base.model":
            continue
        path = (root / result[key].replace("\\", "/")).resolve()
        if key == "base.model":
            if not path.is_dir() or not (path / "config.json").is_file():
                raise InvalidProfile(key, "请选择包含 config.json 的本机 Hugging Face 模型目录（不是 GGUF 文件）。")
        elif not path.is_file() or path.suffix.lower() != ".jsonl":
            raise InvalidProfile(key, "请选择存在的本机 JSONL 文件。")
        else:
            try:
                with path.open(encoding="utf-8") as stream:
                    line = stream.readline(1_000_001)
                if len(line) > 1_000_000:
                    raise ValueError()
                record = json.loads(line)
                if not isinstance(record, dict):
                    raise ValueError()
                if result["prompt.format"] == "native_tools":
                    valid = isinstance(record.get("messages"), list) and isinstance(record.get("tools"), list)
                else:
                    valid = isinstance(record.get("instruction"), str) and isinstance(record.get("output"), str)
                if not valid:
                    raise ValueError()
            except (OSError, UnicodeError, ValueError):
                raise InvalidProfile(key, "首行不是所选格式的有效 JSON 记录；请检查文件内容。") from None
        result[key] = path.as_posix()
    output = (root / result["output.directory"].replace("\\", "/")).resolve()
    if output == Path(output.anchor) or output == root.resolve() or output.is_symlink() or output.is_junction() or (output.exists() and not output.is_dir()):
        raise InvalidProfile("output.directory", "请选择可创建运行子目录的本机目录，不能使用磁盘或仓库根目录。")
    result["output.directory"] = output.as_posix()
    if check_data and Path(result["training.data"]).samefile(result["validation.data"]):
        raise InvalidProfile("validation.data", "验证集必须与训练集分开，不能使用同一个文件。")
    return name.strip(), result
