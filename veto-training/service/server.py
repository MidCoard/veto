"""Local maintainer console. Training stays behind the shared Java API/CLI."""
import argparse
import json
import os
import queue
import re
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from processes import Child
from budget import Budget
from datasets import Datasets
from downloads import Downloads
from profiles import DEFAULTS, InvalidProfile, validate
from assets import describe, entry, identity, include_references, local_path, register, record_count
from readiness import check

ASSETS = Path(__file__).parent / "web" / "dist"
ACTIVE = {"starting", "running", "pause_pending", "pausing", "exporting"}


def stamp():
    return datetime.now(timezone.utc).isoformat()


def read_json(path, default=None):
    if not path.exists():
        return default
    if path.stat().st_size > 2_000_000:
        raise ValueError("State file exceeds its size limit")
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_json(path, value):
    data = json.dumps(value, ensure_ascii=False, indent=2)
    if len(data.encode("utf-8")) > 2_000_000:
        raise ValueError("State exceeds its size limit")
    temporary = path.with_suffix(".tmp")
    temporary.write_text(data, encoding="utf-8")
    temporary.replace(path)


def properties(path):
    # Maintainer profiles use ordinary key=value properties, without Java escapes.
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            if "\\" in value:
                raise ValueError("Profile paths must use forward slashes")
            result[key.strip()] = value.strip()
    return result


def locked(path):
    if not path.exists():
        return False
    import msvcrt
    with path.open("r+b") as stream:
        try:
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            return True
        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        return False


def checkpoints(output):
    if not output.exists() or (output / "merged").exists() or (output / "gguf").exists():
        return []
    result = []
    for path in output.glob("checkpoint-*"):
        if path.is_symlink() or not path.name.removeprefix("checkpoint-").isdigit():
            continue
        try:
            marker = read_json(path / "complete.json")
            if marker and not marker.get("optimization_finished", False):
                result.append(path.name)
        except (OSError, ValueError):
            continue
    return sorted(result, key=lambda name: int(name.split("-")[1]), reverse=True)[:4]


def export_ready(output):
    def regular(path):
        return path.is_file() and not path.is_symlink()

    merged = output / "merged"
    if regular(merged / "config.json") and (any(merged.glob("*.safetensors"))
            or any(merged.glob("pytorch_model*.bin"))):
        return True
    adapter = output / "lora-adapter"
    if regular(adapter / "adapter_config.json") and any(
            regular(adapter / name) for name in ("adapter_model.safetensors", "adapter_model.bin")):
        return True
    for checkpoint in output.glob("checkpoint-*"):
        try:
            marker = read_json(checkpoint / "complete.json")
            if marker and marker.get("optimization_finished") and regular(checkpoint / "adapter_config.json"):
                return True
        except (OSError, ValueError):
            continue
    return False


def directory_bytes(root):
    total = 0
    if not root.exists():
        return total
    for base, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(base) / name
            if path.is_symlink() or path.is_junction():
                raise ValueError("Output directory links are not allowed")
        for name in files:
            try:
                total += (Path(base) / name).stat().st_size
            except FileNotFoundError:
                pass
    return total


class Manager:
    def __init__(self, config, child_factory=Child):
        self.config = config
        self.root = Path(config["repository"]).resolve()
        self.budget = None
        self.datasets = Datasets(self.root / "work/training-datasets", self.reserve_dataset)
        self.datasets.recover()
        self.state_dir = Path(config["stateDirectory"]).resolve()
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.state_dir / "state.json"
        self.lock = threading.RLock()
        self.factory = child_factory
        self.downloads = Downloads(self.root, self.state_dir, child_factory)
        self.worker = None
        self.worker_run = None
        self.events = queue.Queue(maxsize=64)
        self.backend = None
        self.backend_restarts = []
        self.backend_status = "starting"
        self.frontend = None
        self.frontend_restarts = []
        self.frontend_status = "starting"
        self.stopping = False
        self.done = threading.Event()
        self.token = secrets.token_urlsafe(32)
        if "accounting" in config:
            managed_root = str(self.datasets.root)
            if not any(Path(value).resolve() == self.datasets.root.resolve() for value in config["accounting"]["roots"]):
                config["accounting"]["roots"].append(managed_root)
        self.budget = Budget(config["accounting"], self.state_dir) if "accounting" in config else None
        self.last_budget_check = 0
        self.profiles = {p["id"]: p for p in config["profiles"]}
        if len(self.profiles) > 10 or any(not k.replace("-", "").isalnum() for k in self.profiles):
            raise ValueError("Configure at most ten named profiles")
        self.state = read_json(self.path, {"revision": 0, "runs": []})
        self.state.setdefault("customProfiles", [])
        self.state.setdefault("removedProfiles", [])
        self.profiles.update({p["id"]: p for p in self.state["customProfiles"]})
        for identifier in self.state["removedProfiles"]:
            self.profiles.pop(identifier, None)
        for run in self.state["runs"]:
            if run["status"] in ACTIVE:
                run.update(status="interrupted", message="服务上次意外退出；可从完整保存点恢复。")
        if not self.state["runs"]:
            for profile in self.profiles.values():
                source = self.root / profile["properties"]
                output = self.root / properties(source)["output.directory"]
                if output.exists():
                    self.state["runs"].append({"id": profile["id"] + "-existing",
                        "profile": profile["id"], "properties": str(source), "output": str(output),
                        "status": "interrupted", "message": profile.get("previousStatus", "已有运行目录"),
                        "updated": stamp(), "progress": {}})
        if "assets" not in self.state:
            self.state["assets"] = []
            for _, _, values in self.asset_references():
                self.state["assets"] = include_references(self.root, self.state["assets"], values)
        self.save()

    def save(self):
        atomic_json(self.path, self.state)

    def change(self, run, **values):
        run.update(values, updated=stamp())
        self.state["revision"] += 1
        self.save()

    def snapshot(self):
        with self.lock:
            references = self.asset_references()
            runs = []
            for run in reversed(self.state["runs"]):
                row = dict(run)
                row["name"] = run.get("name", self.profiles.get(run["profile"], {}).get("name", run["profile"]))
                row.pop("properties", None)
                row["checkpoints"] = checkpoints(Path(run["output"]))
                row["exportReady"] = run["status"] in ("failed", "interrupted") and export_ready(Path(run["output"]))
                runs.append(row)
            return {"revision": self.state["revision"], "csrf": self.token,
                "backend": self.backend_status, "frontend": self.frontend_status, "stopping": self.stopping,
                "busy": self.worker is not None or self.downloads.worker is not None, "runs": runs,
                "downloads": self.downloads.snapshot(),
                "assets": [describe(asset, references) for asset in self.state["assets"]],
                "templates": self.templates(),
                "datasets": self.datasets.catalog(),
                "profiles": [{"purpose": p.get("purpose", p["id"]), "id": p["id"], "name": p["name"], "scope": p["scope"],
                              "enabled": p.get("enabled", True),
                              "settings": self.profile_settings(p),
                                "counts": self.profile_counts(p)}
                             for p in self.profiles.values()],
                "storage": {str(d): shutil.disk_usage(d).free for d in self.config["volumes"]},
                "reserveBytes": self.config["reserveBytes"],
                "backendUrl": self.config["backendUrl"]}

    def profile_settings(self, profile):
        values = properties(self.root / profile["properties"])
        return {key: values.get(key, default) for key, default in DEFAULTS.items()}

    def profile_counts(self, profile):
        settings = self.profile_settings(profile)
        return {key: record_count(self.root / settings[key]) if settings.get(key) else None
                for key in ("training.data", "validation.data")}

    def templates(self):
        result = []
        for profile in self.config["profiles"]:
            settings = self.profile_settings(profile)
            model = settings["base.model"]
            if model and not (self.root / model).exists():
                cache = Path(self.config.get("environment", {}).get("HF_HOME", str(Path.home() / ".cache/huggingface"))) / "hub" / ("models--" + model.replace("/", "--"))
                ref = cache / "refs/main"
                if ref.is_file():
                    snapshot = cache / "snapshots" / ref.read_text().strip()
                    if (snapshot / "config.json").is_file():
                        settings["base.model"] = snapshot.as_posix()
            result.append({"id": profile["id"], "name": profile["name"], "settings": settings})
        return result

    def check_profile(self, profile):
        return check(self.root, self.config, properties(self.root / profile["properties"]), profile.get("purpose", profile["id"]))

    def register_outputs(self, run):
        output = Path(run["output"])
        paths = [(p, "gguf") for p in sorted((output / "gguf").glob("*.gguf"))[:10]]
        if (output / "merged/config.json").is_file():
            paths.append((output / "merged", "huggingface"))
        known = {identity(Path(a["path"])) for a in self.state["assets"] if a["kind"] == "model"}
        for path, format in paths:
            if identity(path) not in known and len(self.state["assets"]) < 200:
                self.state["assets"].append(entry(path, "model", run.get("name", run["id"]) + " / " + path.name, format))
        run["artifacts"] = [p.as_posix() for p, _ in paths]

    def asset_references(self):
        references = []
        sources = [("tasks", p["name"], self.root / p["properties"])
                   for p in self.profiles.values() if p.get("enabled", True)]
        sources += [("runs", r.get("name", r["id"]), Path(r["properties"])) for r in self.state["runs"]]
        for group, label, source in sources:
            try:
                values = properties(source)
                for key in ("base.model", "training.data", "validation.data"):
                    if values.get(key):
                        values[key] = str(local_path(self.root, values[key]))
                references.append((group, label, values))
            except (OSError, ValueError):
                continue
        for run in self.state["runs"]:
            for artifact in run.get("artifacts", []):
                references.append(("runs", run.get("name", run["id"]), {"base.model": artifact}))
        return references

    def create_profile(self, payload, editing=None):
        if not editing and len(self.profiles) >= 10:
            raise InvalidProfile("name", "目前最多保存 10 个任务。")
        settings_input = dict(payload.get("settings") or {})
        if payload.get("dataset"):
            validate(self.root, payload.get("name"), settings_input, check_data=False)
            path, metadata = self.datasets.metadata(payload["dataset"])
            counts = self.datasets.browse(payload["dataset"], {})["counts"]
            if not counts["train"] or not counts["validation"]:
                raise InvalidProfile("dataset", "数据集需要训练和验证样本。")
            snapshots = self.datasets.export(payload["dataset"], {"datasetRevision": payload.get("datasetRevision")})
            settings_input.update({"training.data": snapshots["train"], "validation.data": snapshots["validation"]})
        name, settings = validate(self.root, payload.get("name"), settings_input)
        if any(p["name"] == name and p["id"] != editing and p.get("enabled", True) for p in self.profiles.values()):
            raise InvalidProfile("name", "已有同名任务，请换一个名称。")
        self.disk_check()
        templates = {p["id"]: p for p in self.config["profiles"]}
        purpose = self.profiles[editing].get("purpose", editing if editing in templates else self.config["profiles"][0]["id"]) if editing else payload.get("template", self.config["profiles"][0]["id"])
        if purpose not in templates:
            raise InvalidProfile("name", "请选择有效的训练模板。")
        shared = properties(self.root / self.config["profiles"][0]["properties"])
        source = properties(self.root / templates[purpose]["properties"])
        runtime = {k: shared[k] for k in ("python", "llama.cpp", "timeout.seconds") if k in shared}
        identifier = editing or "custom-" + secrets.token_hex(6)
        runtime["model.name"] = source.get("model.name", identifier)
        runtime.update(settings)
        assets = include_references(self.root, self.state["assets"], settings)
        runtime.update({"output.directory": (self.root / "veto-training/models" / identifier).as_posix(),
                        "action": "train"})
        path = self.state_dir / (identifier + "-recipe-" + secrets.token_hex(4) + ".properties")
        path.write_text("\n".join(f"{k}={v}" for k, v in runtime.items()), encoding="utf-8")
        profile = {"id": identifier, "purpose": purpose, "name": name, "properties": str(path), "enabled": True,
                   "scope": f"{Path(settings['base.model']).name} · {settings['prompt.format']} · {settings['epochs']} 轮"}
        next_state = dict(self.state,
            customProfiles=[p for p in self.state["customProfiles"] if p["id"] != identifier] + [profile],
            assets=assets, revision=self.state["revision"] + 1)
        atomic_json(self.path, next_state)
        self.state = next_state
        self.profiles[identifier] = profile

    def disk_check(self, output=None):
        if self.budget and time.monotonic() - self.last_budget_check > 30:
            self.budget.check()
            self.last_budget_check = time.monotonic()
        for volume in self.config["volumes"]:
            if shutil.disk_usage(volume).free < self.config["reserveBytes"]:
                raise ValueError("磁盘可用空间不足；训练保持停止，请先释放空间。")
        if directory_bytes(self.state_dir) > self.config["stateLimitBytes"]:
            raise ValueError("管理服务输出额度已用完。")
        if output and directory_bytes(output) > self.config["modelLimitBytes"]:
            raise ValueError("本轮模型输出额度已用完。")

    def env(self, training=False):
        result = dict(os.environ)
        result.update(self.config.get("environment", {}))
        result.update(PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1",
                      TMP=str(self.state_dir), TEMP=str(self.state_dir))
        for key in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
            result[key] = "8" if training else "1"
        return result

    def command(self, path):
        return [self.config["java"], "-Xmx512m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                f"-Djava.io.tmpdir={self.state_dir}",
                "-cp", self.config["trainingJar"], "top.focess.veto.training.TrainingCli",
                str(self.root), str(path)]

    def launch(self, run, action, checkpoint=None):
        self.disk_check(Path(run["output"]))
        for other in self.state["runs"]:
            output = Path(other["output"])
            if locked(output / "training.lock") or locked(output / "worker.lock"):
                raise ValueError("已有训练进程；等待它保存并退出后再继续。")
        profile = properties(Path(run["properties"]))
        profile["action"] = action
        if checkpoint:
            profile["resume.checkpoint"] = (Path(run["output"]) / checkpoint).as_posix()
        command_file = self.state_dir / (run["id"] + "-command.properties")
        command_file.write_text("\n".join(f"{k}={v}" for k, v in profile.items()), encoding="utf-8")
        if action == "export":
            self.change(run, status="exporting", message="正在合并并导出模型，不会重新训练。")
        else:
            self.change(run, status="starting", message="正在加载模型和数据。")
        try:
            self.worker = self.factory(self.command(command_file), self.root, self.env(True),
                self.state_dir / (run["id"] + ".log"), training_memory_limit=self.config.get("trainingMemoryLimitBytes"), on_line=lambda line: self.enqueue(run, line))
            self.worker_run = run
            self.started = time.monotonic()
        except BaseException:
            self.change(run, status="failed", message="无法启动训练进程；检查本机日志。")
            raise

    def enqueue(self, run, line):
        if not line.startswith("{"):
            return
        try:
            self.events.put_nowait((run, line))
        except queue.Full:
            pass  # Keep pipe draining; metrics never decide the training outcome.

    def progress(self, run, line):
        try:
            data = json.loads(line)
        except ValueError:
            return
        if not isinstance(data, dict):
            return
        with self.lock:
            if run["status"] not in ACTIVE:
                return
            # Only structured metrics; never return input text, credentials or raw logs.
            values = {key: value for key, value in data.items()
                      if key in ("step", "epoch", "loss", "eval_loss", "max_steps")
                      and isinstance(value, (int, float))}
            run["progress"].update(values)
            if values:
                run["updated"] = stamp()
            if values and run["status"] == "starting":
                self.change(run, status="running", message="训练进行中。")
            if data.get("type") == "phase_complete" and data.get("phase") == "training":
                self.change(run, status="exporting", message="训练完成，正在合并并导出模型。")

    def upload_dataset(self, identifier, query, length, stream):
        with self.lock:
            if not 0 < length <= 300_000_000:
                raise ValueError("文件需要在 300 MB 以内。")
            path, metadata = self.datasets.metadata(identifier)
            revision = int(query.get("datasetRevision", ["-1"])[0])
            split = query.get("split", [""])[0]
            if revision != metadata["revision"] or split not in ("train", "validation", "test"):
                raise ValueError("数据集已变化或划分无效，请刷新后重试。")
            self.reserve_dataset(length * 2 + 64_000_000)
            temporary = path / ("upload-" + secrets.token_hex(8) + ".jsonl")
            remaining = length
            try:
                with temporary.open("wb") as target:
                    while remaining:
                        chunk = stream.read(min(1_000_000, remaining))
                        if not chunk:
                            raise ValueError("上传中断，未导入任何样本。")
                        if shutil.disk_usage(self.root).free < self.config["reserveBytes"]:
                            raise ValueError("磁盘空间不足，上传已停止。")
                        target.write(chunk)
                        remaining -= len(chunk)
                return self.datasets.mutate(identifier, "import", {"datasetRevision": revision, "split": split,
                    "name": query.get("name", [temporary.name])[0]}, temporary)
            finally:
                temporary.unlink(missing_ok=True)

    def reserve_dataset(self, size):
        if self.budget:
            self.budget.check_scope(self.datasets.root)
            if self.budget.state["used"] + size >= self.budget.config["limitBytes"] - 8_000_000:
                raise ValueError("本次操作超过剩余产出额度。")
        if shutil.disk_usage(self.root).free < size + self.config["reserveBytes"]:
            raise ValueError("磁盘空间不足。")

    def action(self, parts, payload):
        with self.lock:
            if payload.get("revision") != self.state["revision"]:
                raise ValueError("状态已变化，请刷新后重试。")
            if self.stopping:
                raise ValueError("服务正在停止。")
            if parts == ["datasets", "create"]:
                self.reserve_dataset(100_000)
                return {"id": self.datasets.create(payload.get("name"), payload.get("format"))}
            if len(parts) == 3 and parts[0] == "datasets":
                identifier, operation = parts[1:]
                if operation == "browse":
                    return self.datasets.browse(identifier, payload)
                if operation == "preview":
                    return self.datasets.preview(identifier, payload.get("sample"))
                source = local_path(self.root, payload.get("path")) if operation == "import" else None
                return self.datasets.mutate(identifier, operation, payload, source)
            if parts == ["downloads", "create"] or len(parts) == 3 and parts[0] == "downloads":
                operation = "create" if len(parts) == 2 else parts[2]
                identifier = None if len(parts) == 2 else parts[1]
                if operation in ("create", "resume"):
                    if self.worker:
                        raise ValueError("请先暂停训练，再下载模型。")
                    self.disk_check()
                    values = properties(self.root / self.config["profiles"][0]["properties"])
                    return self.downloads.start(payload, self.root / values["python"], self.env(),
                                                self.config["reserveBytes"], self.config.get("downloadLimitBytes", 10_000_000_000), identifier)
                if operation == "pause":
                    self.downloads.pause(identifier)
                    return
                raise ValueError("未知下载操作。")
            if parts == ["profiles", "create"]:
                self.create_profile(payload)
                return
            if parts == ["assets", "create"]:
                asset = register(self.root, self.state["assets"], payload)
                next_state = dict(self.state, assets=[*self.state["assets"], asset], revision=self.state["revision"] + 1)
                atomic_json(self.path, next_state)
                self.state = next_state
                return
            if parts == ["service", "stop"]:
                if self.worker_run and self.worker_run["status"] != "exporting":
                    self.pause(self.worker_run)
                self.stopping = True
                self.state["revision"] += 1
                return
            if len(parts) != 3:
                raise ValueError("未知操作。")
            kind, identifier, action = parts
            if kind == "assets" and action == "remove":
                asset = next((a for a in self.state["assets"] if a["id"] == identifier), None)
                if asset is None:
                    raise ValueError("模型不存在。")
                if asset["kind"] == "model":
                    path = Path(asset["path"])
                    if payload.get("confirmed") is not True:
                        raise ValueError("请确认永久删除模型。")
                    target = path
                    if path.parent.name == "snapshots" and path.parent.parent.name.startswith("models--"):
                        target = path.parent.parent
                    if target.exists():
                        if target.is_symlink() or target.is_junction():
                            raise ValueError("模型路径不能是链接。")
                        if target.is_dir():
                            shutil.rmtree(target)
                        else:
                            target.unlink()
                    self.downloads.remove(path)
                next_state = dict(self.state, assets=[a for a in self.state["assets"] if a["id"] != identifier],
                                  revision=self.state["revision"] + 1)
                atomic_json(self.path, next_state)
                self.state = next_state
                return
            if kind == "profiles" and action in ("check", "update"):
                if identifier not in self.profiles:
                    raise ValueError("任务不存在。")
                if action == "check":
                    return self.check_profile(self.profiles[identifier])
                if any(r["profile"] == identifier and r["status"] in ACTIVE for r in self.state["runs"]):
                    raise ValueError("请先暂停该任务的训练。")
                self.create_profile(payload, identifier)
                return
            if kind == "profiles" and action == "remove":
                if identifier not in self.profiles:
                    raise ValueError("任务不存在。")
                if any(r["profile"] == identifier and r["status"] in ACTIVE for r in self.state["runs"]):
                    raise ValueError("请先暂停该任务的训练。")
                # Remove only the saved task from the list. Frozen run recipes,
                # datasets and all model artifacts retain their current owner.
                next_state = dict(self.state, removedProfiles=[*self.state["removedProfiles"], identifier],
                    customProfiles=[p for p in self.state["customProfiles"] if p["id"] != identifier],
                    revision=self.state["revision"] + 1)
                atomic_json(self.path, next_state)
                self.state = next_state
                del self.profiles[identifier]
                return
            if kind == "profiles" and action == "start":
                if self.worker or self.downloads.worker or identifier not in self.profiles:
                    raise ValueError("已有训练运行或配置不存在。")
                if payload.get("confirmed") is not True:
                    raise ValueError("请确认开始训练。")
                if not self.profiles[identifier].get("enabled", True):
                    raise ValueError("此任务尚未配置可执行的训练计划。")
                if sum(not r.get("archived") for r in self.state["runs"]) >= 20:
                    raise ValueError("管理列表已达到 20 轮上限；请先在页面归档旧记录。")
                self.disk_check()
                profile = self.profiles[identifier]
                values = properties(self.root / profile["properties"])
                readiness = self.check_profile(profile)
                if not readiness["ready"]:
                    raise ValueError("；".join(readiness["issues"]))
                run_id = identifier + "-" + secrets.token_hex(6)
                output = (self.root / values["output.directory"]).parent / run_id
                values["output.directory"] = output.as_posix()
                values["action"] = "train"
                path = self.state_dir / (run_id + ".properties")
                path.write_text("\n".join(f"{k}={v}" for k, v in values.items()), encoding="utf-8")
                run = {"id": run_id, "name": profile["name"], "profile": identifier, "properties": str(path),
                    "output": str(output), "status": "starting", "progress": {}, "updated": stamp()}
                self.state["runs"].append(run)
                try:
                    self.launch(run, "train")
                except (OSError, ValueError):
                    self.change(run, status="failed", message="训练未能启动；检查配置或资源条件后重试。")
                    raise
                return
            run = next((r for r in self.state["runs"] if r["id"] == identifier), None)
            if kind != "runs" or run is None:
                raise ValueError("运行不存在。")
            if action == "logs":
                path = self.state_dir / (run["id"] + ".log")
                if not path.exists():
                    return {"text": "暂无日志。"}
                with path.open("rb") as stream:
                    stream.seek(max(0, path.stat().st_size - 64_000))
                    text = stream.read(64_000).decode("utf-8", errors="replace")
                text = re.sub(r"hf_[A-Za-z0-9]+", "[redacted]", text)
                return {"text": text}
            if action in ("archive", "restore"):
                if run["status"] in ACTIVE:
                    raise ValueError("请先暂停训练。")
                run["archived"] = action == "archive"
                self.state["revision"] += 1
                self.save()
            elif action == "stop":
                if payload.get("confirmed") is not True or run is not self.worker_run or not self.worker:
                    raise ValueError("请确认停止当前训练。")
                self.worker.close()
                self.worker = self.worker_run = None
                self.change(run, status="interrupted", message="已停止，可从已有完整保存点恢复。")
            elif action == "pause":
                self.pause(run)
            elif action == "export":
                if run.get("archived"):
                    raise ValueError("请先恢复运行记录。")
                if payload.get("confirmed") is not True:
                    raise ValueError("请确认重新导出。")
                if self.worker or self.downloads.worker or run["status"] in ACTIVE:
                    raise ValueError("已有训练或导出运行。")
                output = Path(run["output"])
                if not export_ready(output):
                    raise ValueError("没有可用于导出的完整模型或优化完成保存点。")
                profile = properties(Path(run["properties"]))
                artifact = output / "gguf" / (profile.get("model.name", run["profile"]) + "-q4_k_m.gguf")
                if artifact.is_file() and artifact.stat().st_size:
                    self.register_outputs(run)
                    self.change(run, status="completed", message="模型已导出；尚未通过质量验收或部署。")
                    return
                self.launch(run, "export")
            elif action == "resume":
                if run.get("archived"):
                    raise ValueError("请先恢复运行记录。")
                checkpoint = payload.get("checkpoint")
                if self.worker or self.downloads.worker or run["status"] in ACTIVE or checkpoint not in checkpoints(Path(run["output"])):
                    raise ValueError("没有可恢复的完整保存点，或已有训练运行。")
                if payload.get("confirmed") is not True:
                    raise ValueError("请确认恢复训练。")
                self.launch(run, "resume", checkpoint)
            else:
                raise ValueError("未知操作。")

    def pause(self, run):
        if run is not self.worker_run or not self.worker or run["status"] not in ACTIVE:
            raise ValueError("此轮训练没有运行。")
        output = Path(run["output"])
        if (output / "merged").exists():
            raise ValueError("已进入导出阶段；请等待导出结束。")
        if not (output / "training.lock").exists():
            self.change(run, status="pause_pending", message="正在启动，启动后会暂停并保存。")
            return
        values = properties(Path(run["properties"]))
        values["action"] = "pause"
        path = self.state_dir / "pause.properties"
        path.write_text("\n".join(f"{k}={v}" for k, v in values.items()), encoding="utf-8")
        # Use the very same Java public API as CLI callers. No model is loaded.
        result = subprocess.run(self.command(path), cwd=self.root, env=self.env(),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=30, creationflags=0x08000000 if os.name == "nt" else 0)
        if result.returncode:
            raise ValueError("暂停请求未被接受；请刷新训练状态。")
        self.change(run, status="pausing", message="已请求暂停；等待当前步骤完成并保存。")

    def tick(self):
        self.downloads.tick()
        with self.lock:
            for row in self.downloads.snapshot():
                if row["status"] == "completed" and self.downloads.worker is None and not row.get("registered"):
                    path = Path(row["path"])
                    if not any(identity(Path(a["path"])) == identity(path) for a in self.state["assets"]):
                        self.state["assets"].append(entry(path, "model", row["repo"], "huggingface"))
                        self.state["revision"] += 1
                        self.save()
                    with self.downloads.lock:
                        next(r for r in self.downloads.rows if r["id"] == row["id"])["registered"] = True
                        self.downloads.save()
            while not self.events.empty():
                run, line = self.events.get_nowait()
                self.progress(run, line)
            if self.worker:
                run = self.worker_run
                code = self.worker.poll()
                if code is not None:
                    self.worker.close()
                    self.worker = self.worker_run = None
                    output = Path(run["output"])
                    saved = checkpoints(output)
                    marker = output / "paused.checkpoint"
                    if code == 0 and marker.exists() and marker.read_text().strip() in saved:
                        self.change(run, status="paused", message="状态已保存，可以恢复。")
                    elif code == 0 and any((output / "gguf").glob("*.gguf")):
                        self.register_outputs(run)
                        self.change(run, status="completed", message="模型已导出；尚未通过质量验收或部署。")
                    else:
                        message = (f"导出失败（退出码 {code}）；可修复问题后重试，或查看本机日志。"
                                   if run["status"] == "exporting" else
                                   f"训练已停止（退出码 {code}）；检查本机日志。")
                        self.change(run, status="failed", message=message)
                else:
                    try:
                        if run["status"] == "pause_pending" and (Path(run["output"]) / "training.lock").exists():
                            self.pause(run)
                        self.disk_check(Path(run["output"]))
                        self.save()
                        if time.monotonic() - self.started > 172800:
                            raise ValueError("运行已达到 48 小时上限。")
                    except ValueError as error:
                        # Emergency disk stop cannot promise a new checkpoint.
                        self.worker.close()
                        self.worker = self.worker_run = None
                        self.change(run, status="interrupted", message=str(error))
            if self.stopping and not self.worker:
                self.downloads.close()
                if self.backend:
                    self.backend.close()
                    self.backend = None
                if self.frontend:
                    self.frontend.close()
                    self.frontend = None
                self.done.set()
                return
            for name in ("backend", "frontend"):
                try:
                    self.tick_application(name)
                except (OSError, ValueError):
                    setattr(self, name + "_status", "failed")

    def tick_application(self, name):
        cfg = self.config.get(name)
        if not cfg:
            setattr(self, name + "_status", "disabled")
            return
        child = getattr(self, name)
        if child and child.poll() is not None:
            child.close()
            child = None
            setattr(self, name, None)
        if not child:
            try:
                with socket.create_connection(("127.0.0.1", cfg["port"]), timeout=.2):
                    setattr(self, name + "_status", "external")
                    return
            except OSError:
                pass
            now = time.monotonic()
            restarts = [t for t in getattr(self, name + "_restarts") if now - t < 600]
            setattr(self, name + "_restarts", restarts)
            if len(restarts) >= 3:
                setattr(self, name + "_status", "failed")
                return
            self.disk_check()
            env = self.env()
            env.update(cfg.get("environment", {}))
            child = self.factory(cfg["command"], cfg.get("cwd", self.root), env,
                                 self.state_dir / (name + ".log"), backend=True,
                                 memory_limit=10_000_000_000 if name == "backend" else 2 * 1024**3)
            setattr(self, name, child)
            restarts.append(now)
        try:
            with socket.create_connection(("127.0.0.1", cfg["port"]), timeout=.2):
                setattr(self, name + "_status", "running")
        except OSError:
            setattr(self, name + "_status", "starting")

    def close(self):
        self.downloads.close()
        if self.worker:
            self.worker.close()
        if self.backend:
            self.backend.close()
        if self.frontend:
            self.frontend.close()


def handler(manager):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def reply(self, status, data, content_type="application/json; charset=utf-8", nonce=None):
            if not isinstance(data, bytes):
                data = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            styles = f" 'nonce-{nonce}'" if nonce else ""
            self.send_header("Content-Security-Policy", f"default-src 'self'; script-src 'self'; style-src 'self'{styles}; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(data)

        def allowed(self, mutation=False):
            origin = f"http://127.0.0.1:{self.server.server_port}"
            return (self.headers.get("Host") == urlparse(origin).netloc
                    and self.headers.get("Sec-Fetch-Site", "same-origin") in ("same-origin", "none")
                    and (not mutation or (self.headers.get("Origin") == origin
                         and secrets.compare_digest(self.headers.get("X-Veto-CSRF", ""), manager.token))))

        def do_GET(self):
            if not self.allowed():
                self.reply(403, {"error": "仅允许本机同源访问。"})
                return
            route = urlparse(self.path).path
            if route == "/api/state":
                self.reply(200, manager.snapshot())
                return
            if route == "/" and (ASSETS / "index.html").is_file():
                nonce = secrets.token_urlsafe(24)
                html = (ASSETS / "index.html").read_text(encoding="utf-8").replace("__VETO_NONCE__", nonce)
                self.reply(200, html.encode(), "text/html; charset=utf-8", nonce)
            elif re.fullmatch(r"/assets/[A-Za-z0-9_.-]+\.(js|css)", route) and (ASSETS / route[1:]).is_file():
                content_type = "text/javascript" if route.endswith(".js") else "text/css"
                self.reply(200, (ASSETS / route[1:]).read_bytes(), content_type + "; charset=utf-8")
            else:
                self.reply(404, {"error": "页面不存在。"})

        def do_POST(self):
            if not self.allowed(True):
                self.reply(403, {"error": "请求来源无效，请刷新本机页面。"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= (300_000_000 if urlparse(self.path).path.endswith("/upload") else 2_000_000) or not self.path.startswith("/api/"):
                    raise ValueError("请求无效。")
                route = urlparse(self.path)
                upload = re.fullmatch(r"/api/datasets/([a-f0-9]{16})/upload", route.path)
                if upload:
                    result = manager.upload_dataset(upload[1], parse_qs(route.query), length, self.rfile)
                    self.reply(202, result)
                    return
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict):
                    raise ValueError("请求无效。")
                result = manager.action(self.path.removeprefix("/api/").split("/"), payload)
                self.reply(202, result if result is not None else {"accepted": True})
            except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                self.reply(409, {"error": str(error), "field": getattr(error, "field", None)})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    config = read_json(args.config)
    # Binding happens before any process launch; a second start cannot create duplicates.
    server = ThreadingHTTPServer(("127.0.0.1", config["port"]), BaseHTTPRequestHandler)
    server.daemon_threads = True
    manager = Manager(config)
    server.RequestHandlerClass = handler(manager)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        while not manager.done.wait(2):
            manager.tick()
    finally:
        server.shutdown()
        server.server_close()
        manager.close()


if __name__ == "__main__":
    main()
