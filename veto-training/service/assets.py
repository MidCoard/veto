"""Local asset registrations. This module never copies or deletes model/data files."""
import json
import os
import secrets
from pathlib import Path

FIELDS = {"base.model": "model", "training.data": "dataset", "validation.data": "dataset"}
LIMIT = 200


def local_path(root, value):
    if not isinstance(value, str) or not value.strip() or len(value) > 1024 or any(ord(c) < 32 for c in value):
        raise ValueError("请填写有效的本地路径。")
    path = root / value.strip().replace("\\", "/")
    if str(path).startswith(("\\\\", "//")):
        raise ValueError("请选择本地磁盘上的文件。")
    return path.resolve()


def identity(path):
    return os.path.normcase(str(path))


def inspect(path, kind):
    if kind == "model":
        if path.is_dir() and (path / "config.json").is_file():
            return "huggingface"
        if path.is_file() and path.suffix.lower() == ".gguf":
            with path.open("rb") as stream:
                if stream.read(4) == b"GGUF":
                    return "gguf"
        raise ValueError("请选择 Hugging Face 模型目录或有效的 GGUF 文件。")
    if kind != "dataset" or not path.is_file() or path.suffix.lower() != ".jsonl":
        raise ValueError("请选择本地 JSONL 数据集。")
    try:
        with path.open(encoding="utf-8") as stream:
            line = stream.readline(1_000_001)
        if len(line) > 1_000_000:
            raise ValueError()
        record = json.loads(line)
        if isinstance(record, dict):
            if isinstance(record.get("messages"), list) and isinstance(record.get("tools"), list):
                return "native_tools"
            if isinstance(record.get("instruction"), str) and isinstance(record.get("output"), str):
                return "instruction"
    except (UnicodeError, ValueError):
        pass
    raise ValueError("首行不是支持的训练记录，请检查 JSONL 格式。")


def entry(path, kind, name=None, format=None):
    default_name = path.name
    if kind == "model" and path.parent.name == "snapshots" and path.parent.parent.name.startswith("models--"):
        default_name = path.parent.parent.name.removeprefix("models--").replace("--", "/")
    return {"id": secrets.token_hex(8), "kind": kind, "name": name or default_name,
            "path": path.as_posix(), "format": format or ("huggingface" if kind == "model" else "unknown")}


def include_references(root, assets, values):
    result = list(assets)
    known = {(a["kind"], identity(Path(a["path"]))) for a in result}
    for key, kind in FIELDS.items():
        if not values.get(key):
            continue
        path = local_path(root, values[key])
        marker = (kind, identity(path))
        if marker not in known:
            if len(result) >= LIMIT:
                raise ValueError("最多登记 200 项模型与数据集，请先移除不需要的登记。")
            result.append(entry(path, kind, format=values.get("prompt.format") if kind == "dataset" else None))
            known.add(marker)
    return result


def register(root, assets, payload):
    kind, name = payload.get("kind"), payload.get("name")
    if kind not in ("model", "dataset"):
        raise ValueError("资源类型无效。")
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 for c in name):
        raise ValueError("名称需要 1–80 个字符。")
    path = local_path(root, payload.get("path"))
    if any(a["kind"] == kind and identity(Path(a["path"])) == identity(path) for a in assets):
        raise ValueError("该路径已经登记。")
    if len(assets) >= LIMIT:
        raise ValueError("最多登记 200 项模型与数据集。")
    return entry(path, kind, name.strip(), inspect(path, kind))


def describe(asset, references):
    row = dict(asset)
    path = Path(asset["path"])
    try:
        exists = path.is_dir() and (path / "config.json").is_file() if asset["format"] == "huggingface" else path.is_file()
        row["available"] = exists
        row["bytes"] = path.stat().st_size if exists and path.is_file() else None
    except OSError:
        row.update(available=False, bytes=None)
    row["tasks"], row["runs"] = [], []
    for group, label, values in references:
        if any(kind == asset["kind"] and value and identity(Path(value)) == identity(path)
               for key, kind in FIELDS.items() if (value := values.get(key))):
            row[group].append(label)
    row["trainable"] = asset["kind"] == "model" and asset["format"] == "huggingface" and row["available"]
    return row


_COUNT_CACHE = {}


def record_count(path):
    """Count nonblank JSONL lines, bounded to 512 MB/200k records per file."""
    try:
        stat = path.stat()
        key = (str(path), stat.st_size, stat.st_mtime_ns)
        if key in _COUNT_CACHE:
            return _COUNT_CACHE[key]
        if stat.st_size > 512_000_000 or not path.is_file():
            return None
        count, consumed = 0, 0
        with path.open("rb") as stream:
            while line := stream.readline(1_000_001):
                consumed += len(line)
                if len(line) > 1_000_000 or consumed > 512_000_000:
                    return None
                if line.strip():
                    count += 1
                if count > 200_000:
                    return None
        if path.stat().st_mtime_ns != stat.st_mtime_ns:
            return None
        if len(_COUNT_CACHE) >= 128:
            _COUNT_CACHE.clear()
        _COUNT_CACHE[key] = count
        return count
    except OSError:
        return None
