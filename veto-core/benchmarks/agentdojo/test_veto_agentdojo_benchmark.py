import json
import unittest
from pathlib import Path
from urllib.request import Request, urlopen

from agentdojo.functions_runtime import FunctionsRuntime
from agentdojo.task_suite.load_suites import get_suite

from veto_agentdojo_benchmark import (
    AgentDojoMcpServer,
    VetoAgentDojoPipeline,
    relative_report_path,
    select_ids,
)


class AgentDojoMcpServerTest(unittest.TestCase):
    def setUp(self):
        self.suite = get_suite("v1.2.2", "workspace")
        self.server = AgentDojoMcpServer()
        self.server.start()
        runtime = FunctionsRuntime(self.suite.tools)
        environment = self.suite.load_and_inject_default_environment({})
        self.server.set_context(runtime, environment)

    def tearDown(self):
        self.server.close()

    def request(self, method, params=None):
        body = json.dumps(
            {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}
        ).encode("utf-8")
        request = Request(
            self.server.endpoint,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=5) as response:
            payload = response.read().decode("utf-8")
        return json.loads(payload.removeprefix("data: ").strip())

    def test_lists_official_workspace_tools_with_json_schemas(self):
        response = self.request("tools/list")
        tools = response["result"]["tools"]

        self.assertEqual(len(self.suite.tools), len(tools))
        send_email = next(tool for tool in tools if tool["name"] == "send_email")
        self.assertEqual("object", send_email["inputSchema"]["type"])
        self.assertIn("recipients", send_email["inputSchema"]["required"])

    def test_executes_and_records_an_official_workspace_tool(self):
        response = self.request(
            "tools/call", {"name": "get_current_day", "arguments": {}}
        )

        self.assertFalse(response["result"]["isError"])
        self.assertTrue(response["result"]["content"][0]["text"])
        calls = self.server.calls()
        self.assertEqual(1, len(calls))
        self.assertEqual("get_current_day", calls[0].call.function)


class SelectionTest(unittest.TestCase):
    def test_report_paths_are_relative_to_the_report_file(self):
        value = relative_report_path(Path("runs/traces"), Path("runs/report.json"))

        self.assertEqual("traces", value)
        self.assertNotIn(":", value)

    def test_all_selects_every_official_task_id(self):
        self.assertEqual(
            ["user_task_0", "user_task_1"],
            select_ids("all", ["user_task_0", "user_task_1"]),
        )

    def test_explicit_subset_rejects_unknown_ids(self):
        with self.assertRaisesRegex(ValueError, "unknown task ids"):
            select_ids("user_task_0,missing", ["user_task_0", "user_task_1"])

    def test_fixed_proxy_approves_medium_relevance_when_risk_is_not_high(self):
        option = VetoAgentDojoPipeline._approval_policy(
            {
                "relevance": "MEDIUM",
                "danger": "ELEVATED",
                "options": ["ACCEPT_COMMAND_ONCE", "DECLINE_AND_CONTINUE"],
            }
        )
        self.assertEqual("ACCEPT_COMMAND_ONCE", option)

    def test_fixed_proxy_declines_low_relevance(self):
        option = VetoAgentDojoPipeline._approval_policy(
            {
                "relevance": "LOW",
                "danger": "ELEVATED",
                "options": ["ACCEPT_COMMAND_ONCE", "DECLINE_AND_CONTINUE"],
            }
        )
        self.assertEqual("DECLINE_AND_CONTINUE", option)


if __name__ == "__main__":
    unittest.main()
