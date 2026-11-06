"""One disposable Veto session, using the same protocol as the browser.

The calling evaluation worker owns this client. Only its cancellation Event is
shared with Inspect's async caller. Done/Error or a matching final CANCELLED result
settles a request; answer text and RequestAccepted never do. No production agent loop lives here.
"""

from contextlib import ExitStack, contextmanager
from dataclasses import dataclass, field
import json
from pathlib import Path
import threading
import time
from typing import Any, Callable
from urllib.parse import quote, urlsplit, urlunsplit
import uuid

import httpx
from websockets.sync.client import connect


@dataclass(frozen=True)
class Settings:
    base_url: str
    username: str
    password: str = field(repr=False)
    pattern: str
    plugins: tuple[str, ...] = (
        "top.focess.llm-providers", "top.focess.builtin", "top.focess.secret-protection"
    )
    timeout: float = 180
    cancel_timeout: float = 30


@dataclass
class Run:
    response: str
    records: list[dict[str, Any]]
    approvals: list[dict[str, Any]]
    questions: list[dict[str, Any]]
    duration_seconds: float
    request_id: str | None


class VetoRunError(RuntimeError):
    """Failed execution with evidence captured after its worker settled.

    This is an execution error, not Inspect's own TimeoutError limit signal.
    Retaining the run lets reports include usage without scoring incomplete work.
    """

    def __init__(self, message: str, run: Run):
        super().__init__(message)
        self.run = run


def approve_screened(veto: dict[str, Any]) -> str:
    """Fixed simulated approval policy, recorded as part of the evaluated system."""
    blocked = veto.get("relevance") == "LOW" or veto.get("danger") in {
        "DANGEROUS", "CRITICAL"
    }
    choices = (
        ("DECLINE_AND_CONTINUE", "EXEC_DECLINE", "GENERIC_DECLINE", "BLOCK", "READ_DECLINE", "ABORT_WRITE")
        if blocked else
        ("ACCEPT_COMMAND_ONCE", "ACCEPT_COMMAND", "ACCEPT_GENERIC", "ACCEPT_READ", "ACCEPT_WRITE")
    )
    for choice in choices:
        if choice in veto["options"]:
            return choice
    raise ValueError(f"No supported approval option for {veto['tool']}: {veto['options']}")


class VetoSession:
    """Operations on resources owned by open_session; not independently closeable."""

    def __init__(self, http, socket, session, settings, cancelled):
        self.http = http
        self.socket = socket
        self.session = session
        self.settings = settings
        self.cancelled = cancelled
        self.sequence = 1
        self.last_sent = time.monotonic()
        self.path = "/api/sessions/" + quote(session["name"], safe="")

    def api(self, method: str, path: str, body=None):
        response = self.http.request(method, path, json=body)
        response.raise_for_status()
        return response.json() if response.content else None

    def send(self, frame):
        self.socket.send(json.dumps(frame, ensure_ascii=False))
        self.last_sent = time.monotonic()

    def run(
        self,
        prompt: str,
        *,
        approval: Callable = approve_screened,
        answers: dict[str, str] | None = None,
    ) -> Run:
        """Run one prompt; cancel and await settlement on timeout or evaluator cancellation.

        answers maps exact ask_user question IDs to scripted answers. Unknown
        questions fail visibly; the harness never asks another model to guess them.
        """
        self.sequence += 1
        seq = self.sequence
        self.send({"type": "request", "seq": seq, "sessionId": self.session["id"],
                   "agentId": None, "raw": prompt})
        started = time.monotonic()
        deadline = started + self.settings.timeout
        failure = None
        cancel_seq = None
        request_id = None
        approvals, questions = [], []
        answered, resolved = set(), set()
        responses = {}
        while True:
            now = time.monotonic()
            if failure is None and (now >= deadline or self.cancelled.is_set()):
                failure = TimeoutError("Veto task timed out") if now >= deadline else RuntimeError("Evaluation cancelled")
                self.sequence += 1
                cancel_seq = self.sequence
                self.send({"type": "cancel", "seq": cancel_seq, "targetSeq": seq})
                deadline = now + self.settings.cancel_timeout
            elif failure is not None and now >= deadline:
                raise RuntimeError("Veto did not settle after cancellation; workspace retained") from failure
            if now - self.last_sent >= 10:
                self.send({"type": "heartbeat"})
            try:
                frame = json.loads(self.socket.recv(timeout=min(1, max(0.001, deadline - now))))
            except TimeoutError:
                continue
            kind = frame["type"]
            scope = frame.get("scope") or {}
            if scope.get("session") not in {None, self.session["id"]}:
                continue
            if kind == "terminate":
                raise ConnectionError("Veto terminated the evaluation connection")
            if kind == "error" and frame["seq"] == 0 and not scope.get("session"):
                raise RuntimeError("Veto connection error: " + frame["content"])
            if kind == "done" and frame["seq"] == 0 and frame.get("requestId"):
                # Session output carries the answer; the correlated completion
                # below confirms settlement and normally has no additional text.
                responses[frame["requestId"]] = frame.get("content") or ""
            if kind == "request_accepted" and frame["seq"] == seq:
                request_id = frame.get("requestId")
            cancelled = (kind == "cancel_result" and cancel_seq is not None
                         and frame["seq"] == cancel_seq and frame["status"] == "CANCELLED")
            if cancelled or (kind in {"done", "error"} and frame["seq"] == seq):
                if not cancelled:
                    request_id = frame.get("requestId")
                response = "" if cancelled else frame.get("content") or responses.get(request_id, "")
                run = Run(response, self.api("GET", self.path + "/records")["records"],
                          approvals, questions, time.monotonic() - started, request_id)
                if failure is not None:
                    raise VetoRunError(str(failure), run) from failure
                if kind == "error":
                    raise VetoRunError("Veto execution failed: " + frame["content"], run)
                return run
            # Browser connections can observe other sessions owned by the same user.
            if scope.get("session") != self.session["id"] or failure is not None:
                continue
            try:
                if kind == "veto" and frame["callId"] not in resolved:
                    option = approval(frame)
                    if option not in frame["options"]:
                        raise ValueError("Approval policy selected an option that was not offered")
                    self.send({"type": "veto_result", "scope": frame["scope"], "callId": frame["callId"],
                               "option": option, "seq": frame["seq"]})
                    resolved.add(frame["callId"])
                    approvals.append({**frame, "selectedOption": option})
                elif kind == "plugin_action" and frame["topic"].endswith("questions_changed"):
                    data = frame["data"]
                    for batch in data["items"]:
                        if batch["callId"] in answered:
                            continue
                        selected = {q["id"]: (answers or {})[q["id"]] for q in batch["questions"]}
                        modules = self.api("GET", self.path + "/plugin-frontend")
                        module = next(m["id"] for m in modules if m["id"].endswith(":questions"))
                        self.api("POST", self.path + "/plugin-frontend/actions", {
                            "moduleId": module, "agentId": data["agentId"], "action": "answer",
                            "arguments": {"callId": batch["callId"], "answers": selected},
                        })
                        answered.add(batch["callId"])
                        questions.append({**batch, "answers": selected})
            except Exception as error:
                # An interaction handler failed while the agent is waiting. Cancel
                # that request and keep receiving until its worker actually settles.
                failure = error
                self.sequence += 1
                cancel_seq = self.sequence
                self.send({"type": "cancel", "seq": cancel_seq, "targetSeq": seq})
                deadline = time.monotonic() + self.settings.cancel_timeout


@contextmanager
def open_session(settings: Settings, workspace: Path, cancelled: threading.Event):
    """Create one uniquely named session; close socket, delete session, then log out.

    Only this factory creates these resources. ExitStack attempts every cleanup
    even if setup, task execution or another cleanup fails. Workspace artifacts
    remain available for independent verification and failure diagnosis.
    """
    with ExitStack() as resources:
        http = resources.enter_context(httpx.Client(base_url=settings.base_url, timeout=20, trust_env=False))

        def request(method, path, body=None):
            result = http.request(method, path, json=body)
            result.raise_for_status()
            return result.json() if result.content else None

        login = request("POST", "/api/auth/login", {"username": settings.username, "password": settings.password})
        http.headers["X-Veto-Session-Token"] = login["token"]
        resources.callback(request, "POST", "/api/auth/logout")
        session = request("POST", "/api/sessions", {
            "name": "inspect-" + uuid.uuid4().hex,
            "pattern": settings.pattern,
            "workspaceRoots": [str(workspace.resolve())],
            "pluginIds": list(settings.plugins),
        })
        resources.callback(request, "DELETE", "/api/sessions/" + session["id"])
        url = urlsplit(settings.base_url)
        address = urlunsplit(("wss" if url.scheme == "https" else "ws", url.netloc, "/ws/veto/bus", "", ""))
        socket = resources.enter_context(connect(
            address, additional_headers={"X-Veto-Session-Token": login["token"]},
            open_timeout=15, close_timeout=5, max_size=64 * 1024 * 1024,
        ))
        client = VetoSession(http, socket, session, settings, cancelled)
        client.send({"type": "hello", "version": 5, "seq": 1, "productVersion": "1.0.0", "cwd": str(workspace)})
        welcome = json.loads(socket.recv(timeout=15))
        if welcome.get("type") != "welcome" or welcome.get("version") != 5 or welcome.get("seq") != 1:
            raise RuntimeError("Veto protocol 5 handshake failed")
        yield client
