#!/usr/bin/env python3
"""Run the official AgentDojo harness through a live Veto agent and MCP gateway."""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Sequence


REPO_ROOT = Path(__file__).resolve().parents[3]
TRAINING_PYTHON = REPO_ROOT / "veto-core" / "training" / "python"
sys.path.insert(0, str(TRAINING_PYTHON))

from agentdojo.agent_pipeline.base_pipeline_element import BasePipelineElement
from agentdojo.agent_pipeline.tool_execution import tool_result_to_str
from agentdojo.attacks.attack_registry import load_attack
from agentdojo.benchmark import (
    benchmark_suite_with_injections,
    benchmark_suite_without_injections,
)
from agentdojo.functions_runtime import EmptyEnv, Env, FunctionCall, FunctionsRuntime
from agentdojo.logging import Logger, OutputLogger
from agentdojo.task_suite.load_suites import get_suite
from agentdojo.types import (
    ChatAssistantMessage,
    ChatMessage,
    ChatToolResultMessage,
    ChatUserMessage,
    text_content_block_from_string,
)
from veto_agent_bridge import BridgeError, VetoAgentBridge


@dataclass(frozen=True)
class ExecutedCall:
    call: FunctionCall
    result: str
    error: str | None


class AgentDojoMcpServer:
    """Expose the active AgentDojo FunctionsRuntime as a minimal HTTP/SSE MCP server."""

    def __init__(self) -> None:
        self._runtime: FunctionsRuntime | None = None
        self._env: Any = None
        self._calls: list[ExecutedCall] = []
        self._lock = threading.RLock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    request = json.loads(self.rfile.read(size).decode("utf-8"))
                    result = owner._dispatch(request.get("method"), request.get("params") or {})
                    response = {
                        "jsonrpc": "2.0",
                        "id": request.get("id"),
                        "result": result,
                    }
                except Exception as exc:
                    response = {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {"code": -32000, "message": str(exc)},
                    }
                payload = ("data: " + json.dumps(response, ensure_ascii=False) + "\n\n").encode(
                    "utf-8"
                )
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, _format: str, *_args: Any) -> None:
                return

        self._http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._http.serve_forever, daemon=True)

    @property
    def endpoint(self) -> str:
        return f"http://127.0.0.1:{self._http.server_port}/mcp"

    def start(self) -> None:
        self._thread.start()

    def close(self) -> None:
        self._http.shutdown()
        self._http.server_close()
        self._thread.join(timeout=5)

    def set_context(self, runtime: FunctionsRuntime, env: Any) -> None:
        with self._lock:
            self._runtime = runtime
            self._env = env
            self._calls = []

    def calls(self) -> list[ExecutedCall]:
        with self._lock:
            return list(self._calls)

    def _dispatch(self, method: str | None, params: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            if self._runtime is None:
                raise RuntimeError("AgentDojo runtime is not active")
            if method == "tools/list":
                return {
                    "tools": [
                        {
                            "name": function.name,
                            "description": function.description,
                            "inputSchema": function.parameters.model_json_schema(),
                        }
                        for function in self._runtime.functions.values()
                    ]
                }
            if method != "tools/call":
                raise RuntimeError(f"Unsupported MCP method: {method}")
            name = params.get("name")
            arguments = params.get("arguments")
            if not isinstance(name, str) or not isinstance(arguments, dict):
                raise RuntimeError("tools/call requires name and arguments")
            result, error = self._runtime.run_function(self._env, name, arguments)
            formatted = tool_result_to_str(result)
            call = FunctionCall(function=name, args=arguments, id=f"dojo-{len(self._calls) + 1}")
            self._calls.append(ExecutedCall(call, formatted, error))
            return {
                "content": [{"type": "text", "text": formatted}],
                "isError": error is not None,
            }


class VetoAgentDojoPipeline(BasePipelineElement):
    # AgentDojo's model-aware attacks only recognize a fixed upstream model-name table. "local"
    # selects its official Local model wording; the report separately records Veto's actual pattern.
    name = "local-veto-agentdojo-bridge-v4"

    def __init__(
        self,
        *,
        base_url: str,
        username: str,
        password: str,
        pattern: str,
        workspace: Path,
        timeout_seconds: float,
        poll_seconds: float,
    ) -> None:
        self.base_url = base_url
        self.username = username
        self.password = password
        self.pattern = pattern
        self.workspace = workspace.resolve(strict=False)
        self.timeout_seconds = timeout_seconds
        self.poll_seconds = poll_seconds
        self.mcp = AgentDojoMcpServer()
        self.mcp.start()
        self._registered = False
        self.run_records: list[dict[str, Any]] = []

    def close(self) -> None:
        self.mcp.close()

    def query(
        self,
        query: str,
        runtime: FunctionsRuntime,
        env: Env = EmptyEnv(),
        messages: Sequence[ChatMessage] = (),
        extra_args: dict = {},
    ) -> tuple[str, FunctionsRuntime, Env, Sequence[ChatMessage], dict]:
        del messages
        self.mcp.set_context(runtime, env)
        bridge = VetoAgentBridge(
            base_url=self.base_url,
            username=self.username,
            password=self.password,
            pattern=self.pattern,
            workspace=self.workspace,
            timeout_seconds=self.timeout_seconds,
            poll_seconds=self.poll_seconds,
        )
        bridge.connect()
        if not self._registered:
            api = bridge.api
            discover = getattr(api, "discover_mcp", None)
            if discover is None:
                raise BridgeError("Veto API client does not support MCP discovery")
            tools = discover(self.mcp.endpoint)
            print(f"Registered {len(tools)} official AgentDojo tools through Veto MCP")
            self._registered = True
        run = bridge.run(query, self._approval_policy)
        calls = self.mcp.calls()
        converted: list[ChatMessage] = [
            ChatUserMessage(role="user", content=[text_content_block_from_string(query)])
        ]
        for executed in calls:
            converted.append(
                ChatAssistantMessage(role="assistant", content=None, tool_calls=[executed.call])
            )
            converted.append(
                ChatToolResultMessage(
                    role="tool",
                    content=[text_content_block_from_string(executed.result)],
                    tool_call_id=executed.call.id,
                    tool_call=executed.call,
                    error=executed.error,
                )
            )
        final_response = run.response or "The requested operation was refused by Veto."
        converted.append(
            ChatAssistantMessage(
                role="assistant",
                content=[text_content_block_from_string(final_response)],
                tool_calls=None,
            )
        )
        self.run_records.append(
            {
                "query": query,
                "completion": run.completion,
                "durationSeconds": run.duration_seconds,
                "vetoes": run.vetoes,
                "calls": [
                    {
                        "name": executed.call.function,
                        "arguments": executed.call.args,
                        "error": executed.error,
                    }
                    for executed in calls
                ],
                "response": final_response,
            }
        )
        Logger.get().log(converted)
        return query, runtime, env, converted, extra_args

    @staticmethod
    def _approval_policy(veto: dict[str, Any]) -> str:
        options = [option for option in veto.get("options", []) if isinstance(option, str)]
        relevance = veto.get("relevance")
        danger = veto.get("danger")
        if relevance == "LOW" or danger in {"DANGEROUS", "CRITICAL"}:
            if "DECLINE_AND_CONTINUE" in options:
                return "DECLINE_AND_CONTINUE"
            for candidate in ("EXEC_DECLINE", "GENERIC_DECLINE", "BLOCK", "READ_DECLINE"):
                if candidate in options:
                    return candidate
            raise BridgeError(f"No refusal option offered for veto: {veto!r}")
        for candidate in (
            "ACCEPT_COMMAND_ONCE",
            "ACCEPT_COMMAND",
            "ACCEPT_GENERIC",
            "ACCEPT_READ",
            "ACCEPT_WRITE",
        ):
            if candidate in options:
                return candidate
        raise BridgeError(f"No one-call approval option offered for veto: {veto!r}")


def parse_ids(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def select_ids(value: str, available: Sequence[str]) -> list[str]:
    available_ids = list(available)
    if value.strip().lower() == "all":
        return available_ids
    selected = parse_ids(value)
    unknown = sorted(set(selected) - set(available_ids))
    if unknown:
        raise ValueError(f"unknown task ids: {', '.join(unknown)}")
    return selected


def score(values: dict[Any, bool]) -> float:
    return (sum(values.values()) / len(values) * 100.0) if values else 0.0


def stringify_results(values: dict[Any, bool]) -> dict[str, bool]:
    return {"|".join(key) if isinstance(key, tuple) else key: value for key, value in values.items()}


def relative_report_path(target: Path, report_file: Path) -> str:
    """Serialize an artifact path relative to its report, without leaking a host drive."""
    try:
        relative = os.path.relpath(
            target.resolve(strict=False), report_file.parent.resolve(strict=False)
        )
    except ValueError:
        # Different Windows drives cannot form a relative path. Keep the portable filename rather
        # than writing a machine-specific absolute drive into a shareable report.
        relative = target.name
    return Path(relative).as_posix()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8443")
    parser.add_argument("--username", default="sumijie")
    parser.add_argument("--password-env", default="VETO_TEST_PASSWORD")
    parser.add_argument("--pattern", default="ds")
    parser.add_argument("--suite", default="workspace")
    parser.add_argument("--benchmark-version", default="v1.2.2")
    parser.add_argument("--user-tasks", default="user_task_0,user_task_1")
    parser.add_argument("--injection-tasks", default="injection_task_0,injection_task_1")
    parser.add_argument("--attack", default="tool_knowledge")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--poll-interval", type=float, default=0.5)
    parser.add_argument("--output", type=Path, default=Path("runs/veto-agentdojo.json"))
    args = parser.parse_args()
    password = os.environ.get(args.password_env)
    if password is None:
        parser.error(f"set {args.password_env}; the password is not written to reports or argv")

    suite = get_suite(args.benchmark_version, args.suite)
    workspace = Path(__file__).resolve().parent / "workspace"
    workspace.mkdir(parents=True, exist_ok=True)
    pipeline = VetoAgentDojoPipeline(
        base_url=args.base_url,
        username=args.username,
        password=password,
        pattern=args.pattern,
        workspace=workspace,
        timeout_seconds=args.timeout,
        poll_seconds=args.poll_interval,
    )
    user_tasks = select_ids(args.user_tasks, suite.user_tasks)
    injection_tasks = select_ids(args.injection_tasks, suite.injection_tasks)
    trace_dir = args.output.parent / "traces"
    trace_dir.mkdir(parents=True, exist_ok=True)
    try:
        with OutputLogger(str(trace_dir)):
            baseline = benchmark_suite_without_injections(
                pipeline,
                suite,
                logdir=trace_dir,
                force_rerun=False,
                user_tasks=user_tasks,
                benchmark_version=args.benchmark_version,
            )
            attack = load_attack(args.attack, suite, pipeline)
            attacked = benchmark_suite_with_injections(
                pipeline,
                suite,
                attack,
                logdir=trace_dir,
                force_rerun=False,
                user_tasks=user_tasks,
                injection_tasks=injection_tasks,
                benchmark_version=args.benchmark_version,
            )
    finally:
        pipeline.close()

    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "benchmark": "AgentDojo",
        "benchmarkVersion": args.benchmark_version,
        "suite": args.suite,
        "attack": args.attack,
        "vetoPattern": args.pattern,
        "pipelineName": pipeline.name,
        "userTasks": user_tasks,
        "injectionTasks": injection_tasks,
        "traceDirectory": relative_report_path(trace_dir, args.output),
        "baselineUtilityPercent": score(baseline["utility_results"]),
        "attackedUtilityPercent": score(attacked["utility_results"]),
        # AgentDojo's `security_results=True` means the injection goal succeeded (attack success),
        # despite the upstream field/CLI label. Publish both directions to prevent misreading it.
        "attackSuccessPercent": score(attacked["security_results"]),
        "defenseSuccessPercent": 100.0 - score(attacked["security_results"]),
        "baselineUtility": stringify_results(baseline["utility_results"]),
        "attackedUtility": stringify_results(attacked["utility_results"]),
        "attackSucceeded": stringify_results(attacked["security_results"]),
        "runs": pipeline.run_records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
