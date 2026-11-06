"""Harness contracts only; these checks do not measure an agent's capability."""

from contextlib import contextmanager
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from agentdojo.functions_runtime import FunctionsRuntime
from agentdojo.task_suite.load_suites import get_suite
import httpx
from inspect_ai import eval as inspect_eval
from websockets.sync.server import serve

from veto_eval.__main__ import render_report, summarize
from veto_eval.agentdojo import AgentDojoServer
from veto_eval.client import Run, Settings, VetoRunError, VetoSession, open_session
from veto_eval.tasks import build_tasks, run_smoke, SMOKE


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.settings = Settings("http://127.0.0.1", "test-user", "test-secret", "test-pattern")
        self.socket = Mock()
        self.http = httpx.Client(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json={"records": []})))
        self.addCleanup(self.http.close)
        self.client = VetoSession(self.http, self.socket, {"id": "own", "name": "sample"},
                                  self.settings, threading.Event())
        self.http.base_url = self.settings.base_url

    def frames(self, *frames):
        self.socket.recv.side_effect = [json.dumps(frame) for frame in frames]

    def sent(self):
        return [json.loads(call.args[0]) for call in self.socket.send.call_args_list]

    def test_only_correlated_done_settles_request(self):
        self.frames(
            {"type": "assistant", "content": "early text"},
            {"type": "cancel_result", "seq": 2, "status": "CANCELLED"},
            {"type": "done", "seq": 88},
            {"type": "veto", "scope": {"session": "other"}},
            {"type": "error", "scope": {"session": "other"}, "seq": 0},
            {"type": "done", "scope": {"session": "own"}, "seq": 0,
             "requestId": "request", "content": "finished"},
            {"type": "done", "seq": 2, "content": None, "requestId": "request"},
        )
        result = self.client.run("work")
        self.assertEqual("finished", result.response)
        self.assertEqual(7, self.socket.recv.call_count)
        self.assertEqual(["request"], [frame["type"] for frame in self.sent()])

    def test_timeout_waits_for_matching_final_cancellation_without_done(self):
        self.client.settings = replace(self.settings, timeout=0)
        self.frames(
            {"type": "request_accepted", "seq": 2, "requestId": "request"},
            {"type": "cancel_result", "seq": 88, "status": "CANCELLED"},
            {"type": "cancel_result", "seq": 3, "status": "CANCELLED"},
        )
        with self.assertRaisesRegex(VetoRunError, "timed out") as failure:
            self.client.run("work")
        self.assertIsInstance(failure.exception.__cause__, TimeoutError)
        self.assertEqual([], failure.exception.run.records)
        self.assertEqual("request", failure.exception.run.request_id)
        self.assertEqual(3, self.socket.recv.call_count)
        self.assertEqual({"type": "cancel", "seq": 3, "targetSeq": 2}, self.sent()[1])

    def test_interaction_failure_cancels_before_raising(self):
        self.frames(
            {"type": "veto", "scope": {"session": "own"}, "callId": "call",
             "tool": "tool", "options": [], "seq": 4},
            {"type": "cancel_result", "seq": 3, "status": "CANCELLED"},
        )
        with self.assertRaisesRegex(VetoRunError, "No supported approval") as failure:
            self.client.run("work")
        self.assertIsInstance(failure.exception.__cause__, ValueError)
        self.assertEqual(2, self.socket.recv.call_count)
        self.assertEqual("cancel", self.sent()[-1]["type"])

    def test_rejected_or_missing_cancellation_waits_for_normal_completion(self):
        for status in ("REJECTED", "NO_ACTIVE_REQUEST"):
            with self.subTest(status=status):
                self.client.sequence = 1
                self.socket.reset_mock()
                self.client.settings = replace(self.settings, timeout=0)
                self.frames({"type": "cancel_result", "seq": 3, "status": status},
                            {"type": "done", "seq": 2})
                with self.assertRaisesRegex(VetoRunError, "timed out"):
                    self.client.run("work")
                self.assertEqual(2, self.socket.recv.call_count)

    def test_setup_failure_closes_owned_session_and_token(self):
        calls = []

        def api(request):
            calls.append((request.method, request.url.path))
            body = {"token": "private-token"} if request.url.path.endswith("login") else {
                "id": "own", "name": "sample"}
            return httpx.Response(200, json=body)

        http = httpx.Client(base_url=self.settings.base_url, transport=httpx.MockTransport(api))
        with patch("veto_eval.client.httpx.Client", return_value=http), \
                patch("veto_eval.client.connect", side_effect=ConnectionError("offline")):
            with self.assertRaisesRegex(ConnectionError, "offline"):
                with open_session(self.settings, Path.cwd(), threading.Event()):
                    self.fail("Setup should not complete")
        self.assertEqual([("DELETE", "/api/sessions/own"), ("POST", "/api/auth/logout")], calls[-2:])
        self.assertTrue(http.is_closed)

    def test_real_websocket_handshake_and_request(self):
        received = []

        def handler(socket):
            received.append(json.loads(socket.recv()))
            socket.send(json.dumps({"type": "welcome", "version": 5, "seq": 1}))
            received.append(json.loads(socket.recv()))
            socket.send(json.dumps({"type": "done", "seq": 2, "content": "complete"}))

        def api(request):
            if request.url.path.endswith("login"):
                return httpx.Response(200, json={"token": "private-token"})
            if request.method == "POST" and request.url.path == "/api/sessions":
                return httpx.Response(200, json={"id": "own", "name": "sample"})
            return httpx.Response(200, json={"records": []})

        with serve(handler, "127.0.0.1", 0) as server:
            worker = threading.Thread(target=server.serve_forever)
            worker.start()
            try:
                settings = replace(self.settings, base_url=f"http://127.0.0.1:{server.socket.getsockname()[1]}")
                http = httpx.Client(base_url=settings.base_url, transport=httpx.MockTransport(api))
                with patch("veto_eval.client.httpx.Client", return_value=http):
                    with open_session(settings, Path.cwd(), threading.Event()) as session:
                        self.assertEqual("complete", session.run("work").response)
            finally:
                server.shutdown()
                worker.join()
        self.assertEqual(["hello", "request"], [frame["type"] for frame in received])


class EvaluationTest(unittest.TestCase):
    def test_inspect_records_errors_separately_and_never_serializes_credentials(self):
        secret = "credential-must-not-appear-in-eval-log"
        settings = Settings("http://127.0.0.1", "test-user", secret, "test-pattern")
        parent_usage = {"modelCallId": "parent-call", "inputTokens": 4,
                        "outputTokens": 1, "model": "test-model"}
        child_usage = {"modelCallId": "child-call", "inputTokens": 2,
                       "outputTokens": 3, "model": "test-model"}
        records = [
            {"agentId": "parent", "active": True, "llmUsage": [parent_usage]},
            {"agentId": "child", "active": False, "llmUsage": [child_usage]},
            {"agentId": "parent", "active": True, "llmUsage": [parent_usage]},
        ]
        results = [{"response": "done", "utility": True, "duration_seconds": 1, "records": records},
                   TimeoutError("request did not settle")]
        with tempfile.TemporaryDirectory() as directory, \
                patch("veto_eval.tasks.run_smoke", side_effect=results):
            tasks = build_tasks(settings, Path(directory), "smoke", None, [], [])
            logs = inspect_eval(tasks, model=None, log_dir=directory, max_samples=1,
                                fail_on_error=False, display="none", log_shared=False, ctl_server=False)
            summary = summarize(logs)[0]
            self.assertEqual(2, summary["planned"])
            self.assertEqual(1, summary["scored"])
            self.assertEqual(1, len(summary["errors"]))
            self.assertIn("Veto transport timed out: request did not settle", summary["errors"][0]["message"])
            self.assertEqual({"mean": 1, "count": 1}, summary["metrics"]["task_success"])
            completed = next(sample for sample in summary["samples"] if sample["id"] == "file-total")
            self.assertEqual({"measured_calls": 2, "input_tokens": 6, "output_tokens": 4,
                              "models": ["test-model"]}, completed["usage"])
            self.assertNotIn(secret, logs[0].model_dump_json())
            self.assertNotIn("private-token", logs[0].model_dump_json())
            report = render_report({"configuration": {
                "suite": "smoke", "approval_policy": "screened-one-call-v1", "timeout_seconds": 180,
            }, "tasks": [summary]})
            self.assertIn("1/2 samples scored, 1 errors", report)
            self.assertIn("file-total (epoch 1): PASS", report)
            self.assertIn("ask-user (epoch 1): ERROR", report)
            self.assertNotIn(secret, report)

    def test_inspect_retains_cancelled_usage_without_scoring_partial_work(self):
        settings = Settings("http://127.0.0.1", "test-user", "test-secret", "test-pattern")
        run = Run("unfinished", [{"llmUsage": [{"modelCallId": "call", "inputTokens": 8,
                   "outputTokens": 2, "model": "MiniMax-test"}]}], [], [], 180, "request")
        with tempfile.TemporaryDirectory() as directory, \
                patch("veto_eval.tasks.run_smoke", side_effect=VetoRunError("Veto task timed out", run)):
            tasks = build_tasks(settings, Path(directory), "smoke", None, [], [])
            logs = inspect_eval(tasks, model=None, log_dir=directory, max_samples=1, limit=1,
                                fail_on_error=False, display="none", log_shared=False, ctl_server=False)
            summary = summarize(logs)[0]
            self.assertEqual(0, summary["scored"])
            self.assertEqual({}, summary["metrics"])
            self.assertIn("Veto task timed out", summary["errors"][0]["message"])
            self.assertEqual(8, summary["samples"][0]["usage"]["input_tokens"])

    def test_successful_text_does_not_replace_file_verification(self):
        @contextmanager
        def session(*_args):
            yield Mock(run=Mock(return_value=Run("91.50", [], [], [], 1, "request")))

        with tempfile.TemporaryDirectory() as directory, patch("veto_eval.tasks.open_session", session):
            result = run_smoke(None, Path(directory), threading.Event(), SMOKE[0])
        self.assertFalse(result["utility"])
        self.assertIsNone(result["actual"])

    def test_mcp_executes_official_tools_and_preserves_request_ids(self):
        suite = get_suite("v1.2.2", "workspace")
        with AgentDojoServer() as server:
            server.runtime = FunctionsRuntime(suite.tools)
            server.environment = suite.load_and_inject_default_environment({})
            response = httpx.post(f"http://127.0.0.1:{server.http.server_port}/mcp", json={
                "jsonrpc": "2.0", "id": 19, "method": "tools/call",
                "params": {"name": "get_current_day", "arguments": {}},
            }, trust_env=False)
            result = json.loads(response.text.removeprefix("data: "))
            self.assertEqual(19, result["id"])
            self.assertFalse(result["result"]["isError"])
            self.assertEqual("get_current_day", server.calls[0][0].function)
        self.assertFalse(server.worker.is_alive())


if __name__ == "__main__":
    unittest.main()
