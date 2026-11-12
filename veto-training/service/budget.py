"""Cumulative high-water accounting, including configured hidden snapshot roots."""
import json
import os
from pathlib import Path
import threading

from atomic import replace_with_retry


def inventory(roots):
    sizes = {}
    stack = []
    for value in roots:
        path = Path(value)
        if path.is_symlink() or path.is_junction():
            raise ValueError(f"Accounting path is a link: {path}")
        if path.is_file():
            sizes[str(path)] = path.stat().st_size
        elif path.exists():
            stack.append(str(path))
    while stack:
        path = stack.pop()
        try:
            with os.scandir(path) as entries:
                for entry in entries:
                    if entry.is_symlink() or entry.is_junction():
                        raise ValueError(f"Accounting path is a link: {entry.path}")
                    if entry.is_dir(follow_symlinks=False):
                        stack.append(entry.path)
                    else:
                        try:
                            sizes[entry.path] = entry.stat(follow_symlinks=False).st_size
                        except FileNotFoundError:
                            pass
        except FileNotFoundError:
            pass
    return sizes


class Budget:
    def roots(self):
        return self.config["roots"] + [str(path) for directory in self.config.get("rootFiles", [])
                                        for path in Path(directory).iterdir() if path.is_file()]

    def __init__(self, config, state_dir):
        self.lock = threading.RLock()
        self.version = 0
        self.config = config
        self.path = state_dir / "accounting.json"
        if config["chargedBytes"] >= config["limitBytes"] - 8_000_000:
            raise ValueError("累计非模型产出额度不足。")
        if self.path.exists():
            self.state = json.loads(self.path.read_text(encoding="utf-8"))
        else:
            self.state = {"used": config["chargedBytes"], "files": inventory(self.roots())}
            self.persist()
            return
        self.check()

    def check(self):
        while True:
            with self.lock:
                version = self.version
            current = inventory(self.roots())
            with self.lock:
                if version != self.version:
                    continue
                self.record(current)
                return

    def check_scope(self, root):
        with self.lock:
            prefix = os.path.normcase(os.path.normpath(root)) + os.sep
            current = {path: size for path, size in self.state["files"].items()
                       if not os.path.normcase(os.path.normpath(path)).startswith(prefix)}
            current.update(inventory([root]))
            self.record(current)

    def record(self, current):
        previous = self.state["files"]
        self.state["used"] += sum(max(0, size - previous.get(path, 0)) for path, size in current.items())
        self.state["files"] = current
        self.version += 1
        self.persist()
        if self.state["used"] >= self.config["limitBytes"] - 8_000_000:
            raise ValueError("累计非模型产出额度已用完，不能继续启动或写入训练输出。")

    def persist(self):
        data = json.dumps(self.state)
        if len(data) > 16_000_000:
            raise ValueError("Accounting state exceeds its bounded size")
        # This file and its atomic duplicate are charged separately by a fixed
        # allowance in chargedBytes; roots must not include the state directory.
        temp = self.path.with_suffix(".tmp")
        temp.write_text(data, encoding="utf-8")
        replace_with_retry(temp, self.path)
