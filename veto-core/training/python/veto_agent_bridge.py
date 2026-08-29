#!/usr/bin/env python3
"""Reusable bridge between agent benchmarks and Veto's REST/HITL session API."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Protocol


TERMINAL_REFUSAL_OPTIONS = frozenset(
    {
        "EXEC_DECLINE",
        "READ_DECLINE",
        "GENERIC_DECLINE",
        "DECLINE",
        "READ_DENY",
        "BLOCK",
        "ABORT_WRITE",
    }
)


class BridgeError(RuntimeError):
    """A Veto API or bridge contract failure."""


class VetoApiContract(Protocol):
    def login(self, username: str, password: str) -> dict[str, Any]: ...

    def create_session(
        self, name: str, pattern: str, workspace: Path
    ) -> dict[str, Any]: ...

    def history(self, session_name: str) -> list[dict[str, Any]]: ...

    def submit(self, session_name: str, prompt: str) -> None: ...

    def pending_vetoes(self, session_name: str) -> list[dict[str, Any]]: ...

    def resolve_veto(self, session_name: str, call_id: str, option: str) -> None: ...


ApprovalPolicy = Callable[[dict[str, Any]], str]


@dataclass(frozen=True)
class BridgeRun:
    completion: str
    response: str
    turns: list[dict[str, Any]]
    vetoes: list[dict[str, Any]]
    duration_seconds: float


class VetoApi:
    """Small typed client for the Veto endpoints required by agent benchmarks."""

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None

    def request(
        self, method: str, path: str, body: dict[str, Any] | None = None
    ) -> Any:
        headers = {"Accept": "application/json"}
        if self.token is not None:
            headers["X-Veto-Session-Token"] = self.token
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                payload = response.read().decode("utf-8")
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise BridgeError(
                f"{method} {path} returned HTTP {exc.code}: {detail}"
            ) from exc
        except urllib.error.URLError as exc:
            raise BridgeError(f"{method} {path} failed: {exc.reason}") from exc

    def login(self, username: str, password: str) -> dict[str, Any]:
        result = self.request(
            "POST", "/api/auth/login", {"username": username, "password": password}
        )
        if not isinstance(result, dict) or not isinstance(result.get("token"), str):
            raise BridgeError("login response did not contain a session token")
        self.token = result["token"]
        return result

    def create_session(self, name: str, pattern: str, workspace: Path) -> dict[str, Any]:
        result = self.request(
            "POST",
            "/api/sessions",
            {"name": name, "pattern": pattern, "workspaceRoots": str(workspace)},
        )
        if not isinstance(result, dict) or result.get("name") != name:
            raise BridgeError("create-session response did not match the requested session")
        return result

    def discover_mcp(self, base_url: str, auth_token: str = "") -> list[dict[str, Any]]:
        result = self.request(
            "POST",
            "/api/mcp/servers/discover",
            {"baseUrl": base_url, "authToken": auth_token},
        )
        tools = result.get("tools") if isinstance(result, dict) else None
        if not isinstance(tools, list):
            raise BridgeError("MCP discovery response did not contain a tools array")
        return tools

    def history(self, session_name: str) -> list[dict[str, Any]]:
        result = self.request(
            "GET", f"/api/sessions/{urllib.parse.quote(session_name, safe='')}/history"
        )
        if not isinstance(result, list):
            raise BridgeError("history response was not a JSON array")
        return result

    def submit(self, session_name: str, prompt: str) -> None:
        result = self.request(
            "POST",
            f"/api/sessions/{urllib.parse.quote(session_name, safe='')}/prompt",
            {"prompt": prompt},
        )
        if not isinstance(result, dict) or result.get("status") != "started":
            raise BridgeError(f"prompt was not started: {result!r}")

    def pending_vetoes(self, session_name: str) -> list[dict[str, Any]]:
        result = self.request(
            "GET", f"/api/sessions/{urllib.parse.quote(session_name, safe='')}/vetoes"
        )
        if not isinstance(result, list):
            raise BridgeError("pending-veto response was not a JSON array")
        return result

    def resolve_veto(self, session_name: str, call_id: str, option: str) -> None:
        encoded_session = urllib.parse.quote(session_name, safe="")
        encoded_call = urllib.parse.quote(call_id, safe="")
        self.request(
            "POST",
            f"/api/sessions/{encoded_session}/vetoes/{encoded_call}",
            {"option": option},
        )


def terminal_refusal_observed(
    vetoes: list[dict[str, Any]], turns: list[dict[str, Any]]
) -> bool:
    refused_call_ids = {
        veto.get("callId")
        for veto in vetoes
        if veto.get("selectedOption") in TERMINAL_REFUSAL_OPTIONS
        and isinstance(veto.get("callId"), str)
    }
    if not refused_call_ids:
        return False
    for turn in turns:
        if turn.get("type") != "TOOL_RESPONSE":
            continue
        payload = turn.get("payload")
        if not isinstance(payload, dict) or payload.get("call_id") not in refused_call_ids:
            continue
        content = payload.get("content")
        if isinstance(content, str) and content.startswith("REFUSED - "):
            return True
    return False


def turn_number(turn: dict[str, Any]) -> int:
    value = turn.get("turnNumber", 0)
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


class VetoAgentBridge:
    """Run prompts through one Veto session and resolve HITL decisions via a callback."""

    def __init__(
        self,
        *,
        base_url: str,
        username: str,
        password: str,
        pattern: str,
        workspace: str | Path,
        session_name: str | None = None,
        timeout_seconds: float = 90.0,
        poll_seconds: float = 1.0,
        api: VetoApiContract | None = None,
    ) -> None:
        self.username = username
        self._password = password
        self.pattern = pattern
        self.workspace = Path(workspace).resolve(strict=False)
        self.session_name = session_name or f"agent-eval-{uuid.uuid4().hex[:10]}"
        self.timeout_seconds = timeout_seconds
        self.poll_seconds = poll_seconds
        self.api = api or VetoApi(base_url)
        self.login: dict[str, Any] | None = None
        self.session: dict[str, Any] | None = None

    def connect(self) -> dict[str, Any]:
        self.login = self.api.login(self.username, self._password)
        self.session = self.api.create_session(
            self.session_name, self.pattern, self.workspace
        )
        return self.session

    def run(
        self,
        prompt: str,
        approval_policy: ApprovalPolicy,
        *,
        terminal_on_refusal: bool = False,
    ) -> BridgeRun:
        if self.session is None:
            raise BridgeError("bridge is not connected; call connect() before run()")

        started = time.monotonic()
        history = self.api.history(self.session_name)
        before_turn = max((turn_number(turn) for turn in history), default=0)
        observed_vetoes: list[dict[str, Any]] = []
        resolved_calls: set[str] = set()
        self.api.submit(self.session_name, prompt)

        new_turns: list[dict[str, Any]] = []
        response = ""
        completion = "timeout"
        while time.monotonic() - started < self.timeout_seconds:
            for veto in self.api.pending_vetoes(self.session_name):
                call_id = veto.get("callId")
                if not isinstance(call_id, str) or call_id in resolved_calls:
                    continue
                option = approval_policy(veto)
                if not isinstance(option, str) or not option:
                    raise BridgeError(
                        f"approval policy returned an invalid option for call {call_id!r}"
                    )
                recorded = dict(veto)
                recorded["selectedOption"] = option
                observed_vetoes.append(recorded)
                resolved_calls.add(call_id)
                self.api.resolve_veto(self.session_name, call_id, option)

            history = self.api.history(self.session_name)
            new_turns = [
                turn for turn in history if turn_number(turn) > before_turn
            ]
            responses = [
                turn.get("payload", {}).get("content")
                for turn in new_turns
                if turn.get("type") == "ASSISTANT_RESPONSE"
                and isinstance(turn.get("payload"), dict)
            ]
            if responses and isinstance(responses[-1], str):
                response = responses[-1]
                completion = "assistant_response"
                break
            if terminal_on_refusal and terminal_refusal_observed(
                observed_vetoes, new_turns
            ):
                completion = "hitl_refusal"
                break
            time.sleep(self.poll_seconds)

        return BridgeRun(
            completion=completion,
            response=response,
            turns=new_turns,
            vetoes=observed_vetoes,
            duration_seconds=round(time.monotonic() - started, 3),
        )
