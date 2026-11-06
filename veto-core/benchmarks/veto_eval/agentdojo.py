"""Official AgentDojo environments and validators, with Veto as the agent.

The endpoint is registered once in a dedicated evaluation backend. Tool discovery
is process-global in Veto, so stop that backend after the evaluation run.
"""

from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import threading

from agentdojo.agent_pipeline.base_pipeline_element import BasePipelineElement
from agentdojo.agent_pipeline.tool_execution import tool_result_to_str
from agentdojo.attacks.attack_registry import load_attack
from agentdojo.functions_runtime import FunctionCall
from agentdojo.task_suite.load_suites import get_suite
from agentdojo.types import (
    ChatAssistantMessage, ChatToolResultMessage, ChatUserMessage,
    text_content_block_from_string,
)

from .client import open_session


class AgentDojoServer:
    """Own the local MCP server and serialize mutations of the active task world."""

    def __init__(self):
        self.runtime = None
        self.environment = None
        self.calls = []
        self.lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            timeout = 10

            def do_POST(self):
                request_id = None
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 2 * 1024 * 1024:
                        raise ValueError("MCP request exceeds evaluation bound")
                    request = json.loads(self.rfile.read(size))
                    request_id = request.get("id")
                    result = owner.dispatch(request["method"], request.get("params", {}))
                    response = {"jsonrpc": "2.0", "id": request_id, "result": result}
                except Exception as error:
                    response = {"jsonrpc": "2.0", "id": request_id,
                                "error": {"code": -32000, "message": str(error)}}
                payload = ("data: " + json.dumps(response) + "\n\n").encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args):
                pass

        self.http = HTTPServer(("127.0.0.1", 0), Handler)
        self.worker = threading.Thread(target=self.http.serve_forever, name="agentdojo-mcp")
        self.registered = False

    def __enter__(self):
        self.worker.start()
        return self

    def __exit__(self, *_exc):
        self.http.shutdown()
        self.http.server_close()
        self.worker.join()

    def dispatch(self, method, params):
        with self.lock:
            if self.runtime is None:
                raise RuntimeError("No active AgentDojo task")
            if method == "tools/list":
                return {"tools": [
                    {"name": fn.name, "description": fn.description,
                     "inputSchema": fn.parameters.model_json_schema()}
                    for fn in self.runtime.functions.values()
                ]}
            if method != "tools/call":
                raise ValueError(f"Unsupported MCP method: {method}")
            name, args = params["name"], params["arguments"]
            result, error = self.runtime.run_function(self.environment, name, args)
            content = error if error is not None else tool_result_to_str(result)
            call = FunctionCall(function=name, args=args, id=f"dojo-{len(self.calls) + 1}")
            self.calls.append((call, content, error))
            return {"content": [{"type": "text", "text": content}], "isError": error is not None}


class VetoPipeline(BasePipelineElement):
    # Upstream model-aware attack templates recognize "local". This is not the
    # actual Veto model identity, which is recorded independently in the report.
    name = "local-veto-inspect"

    def __init__(self, settings, workspace: Path, cancelled, server: AgentDojoServer):
        self.settings = settings
        self.workspace = workspace
        self.cancelled = cancelled
        self.server = server
        self.run = None

    def query(self, query, runtime, env, messages=(), extra_args=None):
        server = self.server
        with server.lock:
            server.runtime, server.environment, server.calls = runtime, env, []
        try:
            with open_session(self.settings, self.workspace, self.cancelled) as session:
                if not server.registered:
                    session.api("POST", "/api/mcp/servers/discover", {
                        "baseUrl": f"http://127.0.0.1:{server.http.server_port}/mcp",
                        "authToken": "",
                    })
                    server.registered = True
                self.run = session.run(query)
            transcript = [ChatUserMessage(role="user", content=[text_content_block_from_string(query)])]
            for call, content, error in server.calls:
                transcript.append(ChatAssistantMessage(role="assistant", content=None, tool_calls=[call]))
                transcript.append(ChatToolResultMessage(
                    role="tool", content=[text_content_block_from_string(content)],
                    tool_call_id=call.id, tool_call=call, error=error,
                ))
            transcript.append(ChatAssistantMessage(
                role="assistant", content=[text_content_block_from_string(self.run.response)], tool_calls=None,
            ))
            return query, runtime, env, transcript, extra_args or {}
        finally:
            with server.lock:
                server.runtime = None
                server.environment = None


def evaluate_case(settings, workspace, cancelled, server, metadata):
    """Run exactly one fresh official task, without AgentDojo's result cache."""
    suite = get_suite(metadata["benchmark_version"], metadata["suite"])
    user_task = suite.get_user_task_by_id(metadata["user_task"])
    injection = (suite.get_injection_task_by_id(metadata["injection_task"])
                 if metadata["injection_task"] else None)
    pipeline = VetoPipeline(settings, workspace, cancelled, server)
    attack = load_attack("tool_knowledge", suite, pipeline) if injection else None
    injections = attack.attack(user_task, injection) if attack else {}
    utility, attack_success = suite.run_task_with_pipeline(
        pipeline, user_task, injection, injections,
    )
    result = asdict(pipeline.run)
    result["utility"] = utility
    # Upstream returns True in the second slot for clean tasks. It is not a
    # security observation and must not be counted as either attack or defense.
    result["attack_success"] = attack_success if injection else None
    result["calls"] = [{"tool": call.function, "args": call.args, "result": content, "error": error}
                       for call, content, error in server.calls]
    return result
