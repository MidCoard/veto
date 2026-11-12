"""Managed JSONL dataset files; immutable training exports are derived from enabled files."""
import hashlib
import json
import os
import secrets
import shutil
import tempfile
import threading
from pathlib import Path

from atomic import replace_with_retry

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
    replace_with_retry(tmp, path)


def read(path):
    if path.is_symlink() or path.is_junction():
        raise ValueError("不支持链接文件。")
    if path.stat().st_size > (32_000_000 if path.name == "dataset.json" else MAX_RECORD):
        raise ValueError("文件超过读取上限。")
    return json.loads(path.read_text(encoding="utf-8"))


def json_empty_call(message):
    if message.get("role") != "assistant" or not isinstance(message.get("content"), str):
        return False
    try:
        return json.loads(message["content"]) == {"tool_calls": []}
    except ValueError:
        return False


def normalize_native(record, migrate_empty=False):
    replacements = {
        "After all imports succeed, respond exactly DONE.":
            "After all imports succeed, finish with empty assistant content and no tool calls.",
        "If there are no credentials, respond exactly DONE without calling a tool.":
            "If there are no credentials, finish with empty assistant content and do not call a tool.",
        'After all imports succeed, respond exactly {"tool_calls":[]}.':
            "After all imports succeed, finish with empty assistant content and no tool calls.",
        'If there are no credentials, respond exactly {"tool_calls":[]} without calling a tool.':
            "If there are no credentials, finish with empty assistant content and do not call a tool.",
    }
    for message in record.get("messages", []):
        content = message.get("content")
        if isinstance(content, str):
            for old, new in replacements.items():
                content = content.replace(old, new)
            message["content"] = content
        if message.get("role") != "assistant":
            continue
        if migrate_empty and json_empty_call(message):
            message["content"] = ""
        if migrate_empty and message.get("tool_calls") == []:
            del message["tool_calls"]
    definitions = record.pop("tools", [])
    references = record.get("tool_refs", [])
    if not isinstance(definitions, list) or not isinstance(references, list):
        raise ValueError("工具引用必须是名称数组。")
    for tool in definitions:
        function = tool.get("function") if isinstance(tool, dict) else None
        if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"].strip():
            raise ValueError("工具定义需要函数名称。")
        if function["name"] not in references:
            references.append(function["name"])
    record["tool_refs"] = references
    return definitions


def merge_definitions(meta, definitions):
    tools = meta.setdefault("tools", [])
    by_name = {tool["function"]["name"]: tool for tool in tools}
    for tool in definitions:
        function = tool["function"]
        if not isinstance(function.get("parameters", {}), dict):
            raise ValueError("工具参数结构必须是 JSON 对象。")
        existing = by_name.get(function["name"])
        if existing is not None and existing != tool:
            raise ValueError(f"工具 {function['name']} 存在不同定义。")
        if existing is None:
            tools.append(tool)
            by_name[function["name"]] = tool


def valid(record):
    if not isinstance(record, dict):
        raise ValueError("样本必须是 JSON 对象。")
    if isinstance(record.get("messages"), list) and isinstance(record.get("tool_refs"), list):
        messages = record["messages"]
        references = record["tool_refs"]
        if any(not isinstance(name, str) or not name.strip() for name in references) or len(set(references)) != len(references):
            raise ValueError("工具引用必须是不同的工具名称。")
        if not messages:
            raise ValueError("工具调用样本需要至少一条消息，并以 assistant 消息结束。")
        if not isinstance(messages[-1], dict) or messages[-1].get("role") != "assistant":
            raise ValueError("工具调用样本需要至少一条消息，并以 assistant 消息结束。")
        for message in messages:
            if not isinstance(message, dict) or message.get("role") not in ("system", "user", "assistant", "tool"):
                raise ValueError("消息需要 system、user、assistant 或 tool 角色。")
            if not isinstance(message.get("content"), str) and message.get("content") is not None:
                raise ValueError("消息内容必须是文本。")
            if message["role"] == "tool" and not isinstance(message.get("tool_call_id"), str):
                raise ValueError("tool 消息需要 tool_call_id。")
            calls = message.get("tool_calls", [])
            if not isinstance(calls, list):
                raise ValueError("tool_calls 必须是数组。")
            if json_empty_call(message):
                raise ValueError("无工具调用时 content 应为空文本，并省略 tool_calls。")
            if message["role"] == "assistant" and "tool_calls" in message and not calls:
                raise ValueError("无工具调用时请省略 tool_calls 字段。")
            for call in calls:
                function = call.get("function") if isinstance(call, dict) else None
                if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"].strip():
                    raise ValueError("工具调用需要函数名称。")
                if function["name"] not in references:
                    raise ValueError("工具调用必须引用数据集中的工具定义。")
                arguments = function.get("arguments", {})
                if isinstance(arguments, str):
                    try:
                        arguments = json.loads(arguments)
                    except ValueError:
                        raise ValueError("工具参数必须是 JSON 对象。") from None
                if not isinstance(arguments, dict):
                    raise ValueError("工具参数必须是 JSON 对象。")
        return "native_tools"
    if isinstance(record.get("instruction"), str) and isinstance(record.get("output"), str):
        return "instruction"
    raise ValueError("需要 instruction/output 或 messages/tool_refs 字段。")


def prepare_record(meta, record):
    if meta["format"] == "native_tools" and isinstance(record, dict):
        merge_definitions(meta, normalize_native(record))
    format = valid(record)
    if format != meta["format"]:
        raise ValueError("样本结构不匹配。")
    if format == "native_tools":
        known = {tool["function"]["name"] for tool in meta.get("tools", [])}
        if any(name not in known for name in record["tool_refs"]):
            raise ValueError("样本引用了数据集中不存在的工具。")


def remove_tree(path):
    if path.is_symlink() or path.is_junction():
        raise ValueError("不支持链接目录。")
    if path.exists():
        shutil.rmtree(path)


class Datasets:
    def __init__(self, root, reserve=lambda size: None):
        self.root = root
        self.reserve = reserve
        self.jobs = {}
        self.job_lock = threading.Lock()
        self.stopping = False

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
                        if meta["format"] == "native_tools":
                            merge_definitions(meta, normalize_native(record, True))
                        key = (item["split"], not item.get("deleted", False),
                               FILE_NAMES[item["split"]] + ("（已停用）" if item.get("deleted") else ""))
                        groups.setdefault(key, []).append(record)
                    if meta["format"] == "native_tools":
                        meta["version"] = 5
                        meta["revision"] += 1
                self.replace_shards(path, meta, groups)
            else:
                self.clean_unreferenced(path, meta)
                remove_tree(path / "samples")
                native_upgrade = meta["format"] == "native_tools" and meta.get("version", 2) < 5
                oversized = [item for item in meta["files"].values()
                             if item["records"] > SHARD_RECORDS or item["bytes"] > SHARD_BYTES]
                if native_upgrade or oversized:
                    selected = list(meta["files"].values()) if native_upgrade else oversized
                    groups = {}
                    if not (path / "migration.json").exists():
                        for item in selected:
                            with (path / "files" / item["file"]).open(encoding="utf-8") as stream:
                                records = [json.loads(line) for line in stream if line.strip()]
                            if native_upgrade:
                                for record in records:
                                    merge_definitions(meta, normalize_native(record, True))
                            groups[(item["split"], item["enabled"], item["name"])] = records
                        if native_upgrade:
                            meta["version"] = 5
                            meta["revision"] += 1
                    self.replace_shards(path, meta, groups, [item["file"] for item in selected])
                journal = path / "migration.json"
                if journal.exists():
                    remove_tree(Path(read(journal)["staging"]))
                    journal.unlink()

    def replace_shards(self, path, meta, groups, remove=()):
        journal = path / "migration.json"
        if journal.exists():
            recovery = read(journal)
            staging, files = Path(recovery["staging"]), recovery["files"]
            if meta["format"] == "native_tools" and "tools" in recovery:
                meta["tools"] = recovery["tools"]
                meta["revision"] = recovery["revision"]
                meta["version"] = recovery["version"]
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
            journal_data = {"staging": str(staging), "files": files}
            if meta["format"] == "native_tools":
                journal_data.update(tools=meta.get("tools", []), revision=meta["revision"], version=5)
            write(journal, journal_data)
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
        migrated["version"] = 5 if meta["format"] == "native_tools" else 2
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
        write(path / "dataset.json", {"version": 5 if format == "native_tools" else 2, "name": name.strip(), "format": format, "revision": 0, "files": {}, "tools": []})
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

    def record(self, identifier, sample, index):
        path, meta = self.metadata(identifier)
        if sample not in meta["files"]:
            raise ValueError("文件不存在。")
        item = meta["files"][sample]
        if type(index) is not int or not 0 <= index < item["records"]:
            raise ValueError("样本序号无效。")
        records = []
        with (path / "files" / item["file"]).open("rb") as stream:
            while len(records) <= index:
                line = stream.readline(MAX_RECORD + 2)
                if not line:
                    break
                if len(line) > MAX_RECORD + 1:
                    raise ValueError("单条样本超过 1 MB。")
                if line.strip():
                    records.append(json.loads(line))
        if len(records) <= index:
            raise ValueError("样本序号无效。")
        return {"name": item["name"], "record": records[index], "index": index, "total": item["records"],
                "split": item["split"], "enabled": item["enabled"]}

    def signature(self, function):
        return hashlib.sha256(json.dumps(function, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()

    def job_view(self, job):
        return {key: value for key, value in job.items() if key not in ("thread", "function")}

    def active_job(self, identifier):
        return next((job for job in self.jobs.values() if job["dataset"] == identifier and job["status"] == "running"), None)

    def tool_groups(self, identifier):
        path, meta = self.metadata(identifier)
        definitions = {tool["function"]["name"]: tool for tool in meta.get("tools", [])}
        groups = {self.signature(tool["function"]): {"signature": self.signature(tool["function"]),
                  "function": tool["function"], "count": 0} for tool in meta.get("tools", [])}
        with self.job_lock:
            for item in meta["files"].values():
                with (path / "files" / item["file"]).open(encoding="utf-8") as stream:
                    for line in stream:
                        if not line.strip():
                            continue
                        for name in json.loads(line).get("tool_refs", []):
                            tool = definitions.get(name)
                            if tool is None:
                                raise ValueError(f"样本引用了不存在的工具 {name}。")
                            groups[self.signature(tool["function"])]["count"] += 1
            active = self.active_job(identifier)
            return {"groups": sorted(groups.values(), key=lambda group: group["function"].get("name", "")),
                    "activeJob": self.job_view(active) if active else None, "revision": meta["revision"]}

    def add_tool(self, identifier, payload):
        path, meta = self.metadata(identifier)
        if payload.get("datasetRevision") != meta["revision"]:
            raise ValueError("数据集已变化，请刷新后重试。")
        if meta.get("archived"):
            raise ValueError("请先恢复此数据集。")
        if meta["format"] != "native_tools":
            raise ValueError("只有工具调用数据集支持工具定义。")
        function = payload.get("function")
        if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"].strip():
            raise ValueError("工具定义需要函数名称。")
        function["name"] = function["name"].strip()
        if any(tool["function"]["name"] == function["name"] for tool in meta.get("tools", [])):
            raise ValueError("工具名称已存在。")
        self.reserve(64_000_000)
        merge_definitions(meta, [{"type": "function", "function": function}])
        meta["revision"] += 1
        write(path / "dataset.json", meta)
        return {"revision": meta["revision"]}

    def start_bulk(self, identifier, payload):
        path, meta = self.metadata(identifier)
        if payload.get("datasetRevision") != meta["revision"]:
            raise ValueError("数据集已变化，请刷新后重试。")
        if meta.get("archived"):
            raise ValueError("请先恢复此数据集。")
        if meta["format"] != "native_tools":
            raise ValueError("只有工具调用数据集支持批量定义。")
        function = payload.get("function")
        if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"].strip():
            raise ValueError("工具定义需要函数名称。")
        if not isinstance(function.get("parameters", {}), dict):
            raise ValueError("工具参数结构必须是 JSON 对象。")
        groups = self.tool_groups(identifier)["groups"]
        signature = payload.get("signature")
        original = next((group for group in groups if group["signature"] == signature), None)
        if original is None:
            raise ValueError("工具定义分组不存在或已变化。")
        if function["name"] != original["function"]["name"]:
            raise ValueError("共享定义的工具名称不能修改，以免样本引用失效。")
        with self.job_lock:
            if self.stopping:
                raise ValueError("服务正在停止。")
            if self.active_job(identifier):
                raise ValueError("已有批量定义任务正在执行。")
            self.reserve(64_000_000)
            job = {"id": secrets.token_hex(8), "dataset": identifier, "signature": signature, "function": function,
                   "status": "running", "processed": 0, "total": 1, "updated": 0,
                   "revision": meta["revision"], "error": None, "thread": None}
            self.jobs[job["id"]] = job
            job["thread"] = threading.Thread(target=self.run_bulk, args=(job, path, meta), daemon=True,
                                             name=f"veto-dataset-bulk-{job['id']}")
            job["thread"].start()
            return self.job_view(job)

    def run_bulk(self, job, path, meta):
        try:
            for tool in meta["tools"]:
                if self.signature(tool["function"]) == job["signature"]:
                    tool["function"] = job["function"]
                    job["updated"] = 1
                    break
            else:
                raise ValueError("工具定义已变化，请刷新后重试。")
            meta["revision"] += 1
            write(path / "dataset.json", meta)
            job["processed"] = 1
        except BaseException as error:
            job["status"] = "failed"
            job["error"] = str(error)
            return
        job["status"] = "completed"

    def bulk_status(self, payload):
        job = self.jobs.get(payload.get("job"))
        if job is None:
            raise ValueError("批量任务不存在。")
        return self.job_view(job)

    def close(self):
        self.stopping = True
        for job in self.jobs.values():
            if job["status"] == "running":
                job["thread"].join(timeout=5)

    def mutate(self, identifier, operation, payload, source=None):
        if self.active_job(identifier):
            raise ValueError("批量定义任务正在执行，请完成后重试。")
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
        elif operation in ("save", "update_record"):
            self.reserve(MAX_RECORD + 64_000_000)
        created, obsolete = [], []
        try:
            if operation in ("save", "import"):
                split = payload.get("split")
                if split not in SPLITS:
                    raise ValueError("数据划分无效。")
                if operation == "save":
                    record = payload.get("record")
                    prepare_record(meta, record)
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
                                prepare_record(meta, record)
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
                if operation == "update_record":
                    index = payload.get("index")
                    if type(index) is not int or not 0 <= index < item["records"]:
                        raise ValueError("样本序号无效。")
                    replacement = payload.get("record")
                    prepare_record(meta, replacement)
                    source_path = path / "files" / item["file"]
                    with source_path.open("rb") as stream:
                        lines = [line for line in stream if line.strip()]
                    if len(lines) != item["records"]:
                        raise ValueError("文件记录数已变化，请刷新后重试。")
                    data = (json.dumps(replacement, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
                    if len(data) > MAX_RECORD + 1:
                        raise ValueError("单条样本不能超过 1 MB。")
                    lines[index] = data
                    content = b"".join(lines)
                    if len(content) > SHARD_BYTES:
                        raise ValueError("编辑后文件超过 32 MB，请通过导入重新分片。")
                    target = path / "files" / (secrets.token_hex(16) + ".jsonl")
                    with target.open("xb") as stream:
                        stream.write(content)
                        stream.flush()
                        os.fsync(stream.fileno())
                    created.append(target)
                    obsolete.append(source_path)
                    item["file"] = target.name
                    item["bytes"] = len(content)
                elif operation == "toggle":
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
        if self.active_job(identifier):
            raise ValueError("批量定义任务正在执行，请完成后重试。")
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
        definitions = {tool["function"]["name"]: tool for tool in meta.get("tools", [])}
        definition_bytes = sum(len(json.dumps(tool, ensure_ascii=False, separators=(",", ":"))) for tool in definitions.values())
        record_count = sum(item["records"] for item in items)
        self.reserve(source_bytes + definition_bytes * record_count + 2_000_000)
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
                            if meta["format"] != "native_tools":
                                shutil.copyfileobj(source, stream, 1_000_000)
                            else:
                                for raw in source:
                                    if not raw.strip():
                                        continue
                                    record = json.loads(raw)
                                    record["tools"] = [definitions[name] for name in record["tool_refs"]]
                                    del record["tool_refs"]
                                    stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
                        counts[split] += item["records"]
                    stream.flush()
                    os.fsync(stream.fileno())
            write(staging / "manifest.json", dict(dataset=identifier, revision=meta["revision"], counts=counts))
            staging.rename(folder)
        except BaseException:
            remove_tree(staging)
            raise
        return {split: str(folder / (split + ".jsonl")) for split in SPLITS if counts[split]}
