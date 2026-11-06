"""Managed JSONL dataset files; immutable training exports are derived from enabled files."""
import json
import os
import secrets
import shutil
import tempfile
from pathlib import Path

SPLITS = ("train", "validation", "test")
MAX_BYTES = 300_000_000
MAX_RECORD = 1_000_000
SHARD_RECORDS = 1000
SHARD_BYTES = 32_000_000
FILE_NAMES = {"train": "训练.jsonl", "validation": "验证.jsonl", "test": "测试.jsonl"}


def write(path, value):
    data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(data) > (32_000_000 if path.name == "dataset.json" else MAX_RECORD):
        raise ValueError("单条样本不能超过 1 MB。")
    tmp = path.with_suffix(".tmp")
    with tmp.open("wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    tmp.replace(path)


def read(path):
    if path.is_symlink() or path.is_junction():
        raise ValueError("不支持链接文件。")
    if path.stat().st_size > (32_000_000 if path.name == "dataset.json" else MAX_RECORD):
        raise ValueError("文件超过读取上限。")
    return json.loads(path.read_text(encoding="utf-8"))


def valid(record):
    if not isinstance(record, dict):
        raise ValueError("样本必须是 JSON 对象。")
    if isinstance(record.get("messages"), list) and isinstance(record.get("tools"), list):
        return "native_tools"
    if isinstance(record.get("instruction"), str) and isinstance(record.get("output"), str):
        return "instruction"
    raise ValueError("需要 instruction/output 或 messages/tools 字段。")


def remove_tree(path):
    if path.is_symlink() or path.is_junction():
        raise ValueError("不支持链接目录。")
    if path.exists():
        shutil.rmtree(path)


class Datasets:
    def __init__(self, root, reserve=lambda size: None):
        self.root = root
        self.reserve = reserve

    def shard(self, records, name, split, enabled):
        chunks, data, count = [], bytearray(), 0
        for record in records:
            line = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
            if count and (count >= SHARD_RECORDS or len(data) + len(line) > SHARD_BYTES):
                chunks.append(bytes(data))
                data.clear()
                count = 0
            data.extend(line)
            count += 1
        if data:
            chunks.append(bytes(data))
        files = {}
        total = len(chunks)
        for index, content in enumerate(chunks, 1):
            identifier, filename = secrets.token_hex(8), secrets.token_hex(16) + ".jsonl"
            files[identifier] = dict(file=filename, name=name if total == 1 else f"{name} · {index}/{total}",
                                     split=split, enabled=enabled, records=0, bytes=len(content), content=content)
        return files

    def recover(self):
        for metadata in self.root.glob("*/dataset.json"):
            path = self.directory(metadata.parent.name)
            meta = read(metadata)
            if "samples" in meta:
                groups = {}
                if not (path / "migration.json").exists():
                    for item in meta["samples"].values():
                        record = read(path / "samples" / item["file"])
                        key = (item["split"], not item.get("deleted", False),
                               FILE_NAMES[item["split"]] + ("（已停用）" if item.get("deleted") else ""))
                        groups.setdefault(key, []).append(record)
                self.replace_shards(path, meta, groups)
            else:
                self.clean_unreferenced(path, meta)
                remove_tree(path / "samples")
                oversized = [item for item in meta["files"].values()
                             if item["records"] > SHARD_RECORDS or item["bytes"] > SHARD_BYTES]
                if oversized:
                    groups = {}
                    if not (path / "migration.json").exists():
                        for item in oversized:
                            with (path / "files" / item["file"]).open(encoding="utf-8") as stream:
                                records = [json.loads(line) for line in stream if line.strip()]
                            groups[(item["split"], item["enabled"], item["name"])] = records
                    self.replace_shards(path, meta, groups, [item["file"] for item in oversized])
                journal = path / "migration.json"
                if journal.exists():
                    remove_tree(Path(read(journal)["staging"]))
                    journal.unlink()

    def replace_shards(self, path, meta, groups, remove=()):
        journal = path / "migration.json"
        if journal.exists():
            recovery = read(journal)
            staging, files = Path(recovery["staging"]), recovery["files"]
        else:
            source_bytes = sum(len(json.dumps(record, ensure_ascii=False, separators=(",", ":"))) + 1
                               for records in groups.values() for record in records)
            staging = Path(tempfile.gettempdir()) / "veto-dataset-migration" / path.name
            remove_tree(staging)
            staging.mkdir(parents=True)
            if shutil.disk_usage(staging.parent).free < source_bytes + 64_000_000:
                raise ValueError("迁移暂存空间不足。")
            files = {}
            for (split, enabled, name), records in groups.items():
                for identifier, item in self.shard(records, name, split, enabled).items():
                    content = item.pop("content")
                    with (staging / item["file"]).open("xb") as stream:
                        stream.write(content)
                        stream.flush()
                        os.fsync(stream.fileno())
                    item["records"] = content.count(b"\n")
                    files[identifier] = item
            write(journal, {"staging": str(staging), "files": files})
        if "samples" in meta:
            remove_tree(path / "samples")
        else:
            for filename in remove:
                (path / "files" / filename).unlink(missing_ok=True)
        target = path / "files"
        target.mkdir(exist_ok=True)
        for item in files.values():
            destination = target / item["file"]
            if not destination.is_file() or destination.stat().st_size != item["bytes"]:
                shutil.copyfile(staging / item["file"], destination)
            with destination.open("r+b") as stream:
                os.fsync(stream.fileno())
            (staging / item["file"]).unlink(missing_ok=True)
        migrated = {key: value for key, value in meta.items() if key != "samples"}
        migrated["files"] = files if "samples" in meta else {
            **{key: item for key, item in meta["files"].items() if item["file"] not in remove}, **files}
        migrated["version"] = 2
        write(path / "dataset.json", migrated)
        remove_tree(staging)
        journal.unlink(missing_ok=True)

    def clean_unreferenced(self, path, meta):
        referenced = {item["file"] for item in meta["files"].values()}
        directory = path / "files"
        if directory.is_symlink() or directory.is_junction():
            raise ValueError("不支持链接目录。")
        if not directory.exists():
            return
        for file in directory.iterdir():
            if file.is_symlink() or file.is_junction():
                raise ValueError("不支持链接文件。")
            if (file.suffix in (".jsonl", ".tmp") and len(file.stem) == 32
                    and all(c in "0123456789abcdef" for c in file.stem)
                    and file.name not in referenced):
                file.unlink()

    def directory(self, identifier):
        if not isinstance(identifier, str) or len(identifier) != 16 or any(c not in "0123456789abcdef" for c in identifier):
            raise ValueError("数据集 ID 无效。")
        path = self.root / identifier
        if path.is_symlink() or path.is_junction() or not (path / "dataset.json").is_file():
            raise ValueError("数据集不存在。")
        return path

    def catalog(self):
        return [dict(id=p.parent.name, name=(meta := read(p))["name"], format=meta["format"], revision=meta["revision"], archived=meta.get("archived", False)) for p in sorted(self.root.glob("*/dataset.json"))][:50]

    def create(self, name, format):
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or format not in ("instruction", "native_tools"):
            raise ValueError("请填写名称并选择样本结构。")
        if len(self.catalog()) >= 50:
            raise ValueError("最多 50 个数据集。")
        identifier = secrets.token_hex(8)
        path = self.root / identifier
        (path / "files").mkdir(parents=True)
        write(path / "dataset.json", {"version": 2, "name": name.strip(), "format": format, "revision": 0, "files": {}})
        return identifier

    def metadata(self, identifier):
        path = self.directory(identifier)
        return path, read(path / "dataset.json")

    def browse(self, identifier, payload):
        _, meta = self.metadata(identifier)
        query = str(payload.get("query", "")).casefold()[:200]
        split = payload.get("split", "")
        page = max(0, min(int(payload.get("page", 0)), 1000))
        rows, counts, disabled = [], dict.fromkeys(SPLITS, 0), 0
        for key, item in meta["files"].items():
            if item["enabled"]:
                counts[item["split"]] += item["records"]
            else:
                disabled += item["records"]
            if split and split != item["split"]:
                continue
            if query and query not in item["name"].casefold() and query not in key:
                continue
            rows.append(dict(id=key, name=item["name"], split=item["split"], enabled=item["enabled"],
                             records=item["records"], bytes=item["bytes"]))
        return dict(rows=rows[page*20:(page+1)*20], total=len(rows), counts=counts, disabled=disabled,
                    revision=meta["revision"])

    def preview(self, identifier, sample):
        path, meta = self.metadata(identifier)
        if sample not in meta["files"]:
            raise ValueError("文件不存在。")
        item = meta["files"][sample]
        records = []
        with (path / "files" / item["file"]).open("rb") as stream:
            while len(records) < 3:
                line = stream.readline(MAX_RECORD + 2)
                if not line:
                    break
                if len(line) > MAX_RECORD + 1:
                    raise ValueError("单条样本超过 1 MB。")
                if line.strip():
                    records.append(json.loads(line))
        return {"name": item["name"], "records": records, "split": item["split"], "enabled": item["enabled"]}

    def mutate(self, identifier, operation, payload, source=None):
        path, meta = self.metadata(identifier)
        if payload.get("datasetRevision") != meta["revision"]:
            raise ValueError("数据集已变化，请刷新后重试。")
        if operation in ("rename", "archive", "restore_dataset"):
            if operation == "rename":
                name = payload.get("name")
                if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 for c in name):
                    raise ValueError("请输入 1–80 个字符的名称。")
                meta["name"] = name.strip()
            else:
                meta["archived"] = operation == "archive"
            self.reserve(64_000_000)
            meta["revision"] += 1
            write(path / "dataset.json", meta)
            return {"revision": meta["revision"]}
        if meta.get("archived"):
            raise ValueError("请先恢复此数据集。")
        if operation == "import":
            if source is None or not source.is_file() or source.stat().st_size > MAX_BYTES:
                raise ValueError("请选择不超过 300 MB 的 JSONL 文件。")
            source_limit = source.stat().st_size
            self.reserve(source_limit + 64_000_000)
        elif operation == "save":
            self.reserve(MAX_RECORD + 64_000_000)
        created, obsolete = [], []
        try:
            if operation in ("save", "import"):
                split = payload.get("split")
                if split not in SPLITS:
                    raise ValueError("数据划分无效。")
                if operation == "save":
                    record = payload.get("record")
                    if valid(record) != meta["format"]:
                        raise ValueError("样本结构不匹配。")
                    data = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
                    if len(data) > MAX_RECORD + 1:
                        raise ValueError("单条样本不能超过 1 MB。")
                    records = [record]
                else:
                    records = []
                    consumed = 0
                    with source.open("rb") as input_stream:
                        while line := input_stream.readline(MAX_RECORD + 2):
                            consumed += len(line)
                            if consumed > source_limit:
                                raise ValueError("导入期间源文件发生变化，请重试。")
                            if len(line) > MAX_RECORD + 1:
                                raise ValueError("单条样本超过 1 MB。")
                            if line.strip():
                                record = json.loads(line)
                                if valid(record) != meta["format"]:
                                    raise ValueError("样本结构不匹配。")
                                records.append(record)
                if not records:
                    raise ValueError("文件没有样本。")
                name = payload.get("name") or (source.name if source is not None else "手动样本.jsonl")
                if not isinstance(name, str) or not 1 <= len(name.strip()) <= 160 or any(ord(c) < 32 for c in name):
                    raise ValueError("文件名无效。")
                shards = self.shard(records, name.strip(), split, True)
                if len(meta["files"]) + len(shards) > 20000:
                    raise ValueError("数据集达到 20,000 个文件上限。")
                if sum(item["bytes"] for item in meta["files"].values()) + sum(item["bytes"] for item in shards.values()) > MAX_BYTES:
                    raise ValueError("数据集达到 300 MB 上限。")
                for key, item in shards.items():
                    content = item.pop("content")
                    target = path / "files" / item["file"]
                    created.append(target)
                    with target.open("xb") as stream:
                        stream.write(content)
                        stream.flush()
                        os.fsync(stream.fileno())
                    item["records"] = content.count(b"\n")
                    meta["files"][key] = item
            else:
                key = payload.get("sample")
                if key not in meta["files"]:
                    raise ValueError("文件不存在。")
                item = meta["files"][key]
                if operation == "toggle":
                    if not isinstance(payload.get("enabled"), bool):
                        raise ValueError("文件状态无效。")
                    item["enabled"] = payload["enabled"]
                elif operation == "split":
                    if payload.get("split") not in SPLITS:
                        raise ValueError("数据划分无效。")
                    item["split"] = payload["split"]
                elif operation == "delete":
                    obsolete.append(path / "files" / item["file"])
                    del meta["files"][key]
                else:
                    raise ValueError("未知文件操作。")
            meta["revision"] += 1
            write(path / "dataset.json", meta)
        except BaseException:
            for file in created:
                file.unlink(missing_ok=True)
            raise
        for file in obsolete:
            file.unlink(missing_ok=True)
        return {"revision": meta["revision"]}

    def export(self, identifier, payload):
        path, meta = self.metadata(identifier)
        if payload.get("datasetRevision") != meta["revision"]:
            raise ValueError("数据集已变化，请刷新后重试。")
        if meta.get("archived"):
            raise ValueError("请先恢复此数据集。")
        for manifest in sorted((path / "exports").glob(str(meta["revision"]) + "-*/manifest.json")):
            if manifest.parent.name.endswith(".pending"):
                continue
            exported = read(manifest)
            paths = {split: str(manifest.parent / (split + ".jsonl")) for split, count in exported["counts"].items() if count}
            if all(Path(file).is_file() and Path(file).stat().st_size for file in paths.values()):
                return paths
        items = [item for item in meta["files"].values() if item["enabled"]]
        if not any(item["split"] == "train" for item in items):
            raise ValueError("请先添加训练样本。")
        source_bytes = sum(item["bytes"] for item in items)
        if source_bytes > MAX_BYTES:
            raise ValueError("快照超过 300 MB。")
        self.reserve(source_bytes + 2_000_000)
        folder = path / "exports" / (str(meta["revision"]) + "-" + secrets.token_hex(4))
        staging = folder.with_name(folder.name + ".pending")
        staging.mkdir(parents=True)
        counts = dict.fromkeys(SPLITS, 0)
        try:
            for split in SPLITS:
                with (staging / (split + ".jsonl")).open("wb") as stream:
                    for item in items:
                        if item["split"] != split:
                            continue
                        with (path / "files" / item["file"]).open("rb") as source:
                            shutil.copyfileobj(source, stream, 1_000_000)
                        counts[split] += item["records"]
                    stream.flush()
                    os.fsync(stream.fileno())
            write(staging / "manifest.json", dict(dataset=identifier, revision=meta["revision"], counts=counts))
            staging.rename(folder)
        except BaseException:
            remove_tree(staging)
            raise
        return {split: str(folder / (split + ".jsonl")) for split in SPLITS if counts[split]}
