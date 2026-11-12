"""Content-free training location, exception evidence and independent watchdogs."""
import json
import os
import subprocess
import threading
import time
import traceback


class RuntimeMonitor:
    def __init__(self, run_id, emit, slow_seconds=120, stall_seconds=600):
        if not 0 < slow_seconds < stall_seconds:
            raise ValueError("Require 0 < slow timeout < no-progress timeout")
        self.emit = emit
        self.slow_seconds = slow_seconds
        self.stall_seconds = stall_seconds
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.location = dict(run_id=run_id, optimizer_step=None, micro_step=None,
                             sample_ids=None, input_tokens=None, phase="initializing")
        self.last_progress = time.monotonic()
        self.started_at = time.time()
        self.reported = None

    def report(self, kind, **evidence):
        with self.lock:
            record = dict(self.location)
            elapsed = time.monotonic() - self.last_progress
        record.update(type="runtime_diagnostic", kind=kind, timestamp=time.time(),
                      discovered_at=time.time(), started_at=self.started_at,
                      seconds_without_progress=elapsed,
                      progress_state="no_progress" if elapsed >= self.stall_seconds else
                      "slow_step" if elapsed >= self.slow_seconds else "recent_progress")
        record.update(evidence)
        self.emit(record)

    def progress(self, **location):
        with self.lock:
            self.location.update(location)
            self.last_progress = time.monotonic()
            previous = self.reported
            self.reported = None
        if previous:
            self.report("progress_resumed")

    def exception(self, error):
        # No locals or input tensors: preserve the exception, not sample contents.
        code = getattr(error, "cuda_error_code", None)
        if not isinstance(code, (int, str)):
            code = None
        self.report("training_exception", exception_type=type(error).__qualname__,
                    message=str(error), traceback="".join(traceback.format_exception(error)),
                    cuda_error_code=code, code_status="available" if code is not None else "unknown",
                    failed_state_recoverable=False)

    def start(self):
        # Establish the cursor before admitting any training work.
        self.initial_windows = None
        if os.name == "nt":
            try:
                self.initial_windows = self.query_windows()
            except BaseException as error:
                self.report("monitor_unavailable", monitor="windows", message=str(error))
        self.threads = [threading.Thread(target=self.watch_progress, daemon=True),
                        threading.Thread(target=self.watch_windows, daemon=True)]
        for thread in self.threads:
            thread.start()

    def close(self):
        self.stopped.set()
        for thread in self.threads:
            thread.join(timeout=1)
        self.report("monitor_ended", ended_at=time.time())

    def check_progress(self):
        with self.lock:
            elapsed = time.monotonic() - self.last_progress
            state = "no_progress" if elapsed >= self.stall_seconds else "slow_step" if elapsed >= self.slow_seconds else None
            if state == self.reported:
                return
            self.reported = state
        if state:
            self.report(state, message="No completed training boundary observed; this is not a GPU TDR diagnosis")

    def watch_progress(self):
        try:
            while not self.stopped.wait(1):
                self.check_progress()
        except BaseException as error:
            self.report("monitor_unavailable", monitor="progress", message=str(error))

    def query_windows(self, cursor=None):
        # Cursor covers all System records, including unrelated providers.
        predicate = "" if cursor is None else f" | Where-Object {{ $_.RecordId -gt {int(cursor)} }}"
        script = "$ErrorActionPreference='Stop'; $all=@(Get-WinEvent -LogName System -MaxEvents 2048); "
        script += "$events=@($all" + predicate + "); "
        script += "@{cursor=($all | Measure-Object RecordId -Maximum).Maximum; oldest=($all | Measure-Object RecordId -Minimum).Minimum; events=@($events | Where-Object { $_.ProviderName -match 'nvlddmkm|Display' -and $_.Level -le 2 } | ForEach-Object { @{event_time=$_.TimeCreated.ToUniversalTime().ToString('o'); provider=$_.ProviderName; event_id=$_.Id; record_id=$_.RecordId; message=$_.Message; xml=$_.ToXml()} })} | ConvertTo-Json -Depth 6 -Compress"
        result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                                capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=20, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or "Windows event query failed")
        return json.loads(result.stdout)

    def watch_windows(self):
        if os.name != "nt":
            self.report("monitor_unavailable", monitor="windows", message="Windows event monitor unavailable on this platform")
            return
        try:
            initial = getattr(self, "initial_windows", None)
            if hasattr(self, "initial_windows") and initial is None:
                return
            if initial is None:
                initial = self.query_windows()
            cursor = initial["cursor"]
            if cursor is None:
                raise RuntimeError("System event cursor unavailable")
            self.report("windows_monitor_started", cursor=cursor,
                        historical_events=initial["events"], message="Historical events are not current-run alerts")
            while not self.stopped.wait(2):
                batch = self.query_windows(cursor)
                if batch["oldest"] > cursor + 1 or batch["cursor"] < cursor:
                    self.report("monitor_incomplete", monitor="windows", message="System event cursor gap or log reset")
                for event in batch["events"]:
                    self.report("windows_driver_event", **event,
                                attribution="Temporal correlation only; process and GPU cause unknown")
                cursor = batch["cursor"]
        except BaseException as error:
            self.report("monitor_unavailable", monitor="windows", message=str(error))
