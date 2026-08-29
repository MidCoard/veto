import unittest

from veto_agent_bridge import VetoAgentBridge


class FakeVetoApi:
    def __init__(self, *, turns, vetoes=None):
        self.turns = turns
        self.vetoes = vetoes or []
        self.submitted = False
        self.resolutions = []

    def login(self, username, password):
        return {"username": username, "role": "ADMIN", "token": "not-reported"}

    def create_session(self, name, pattern, workspace):
        return {"id": "session-id", "name": name, "pattern": pattern}

    def history(self, _session_name):
        return self.turns if self.submitted else []

    def submit(self, _session_name, _prompt):
        self.submitted = True

    def pending_vetoes(self, _session_name):
        if not self.submitted:
            return []
        resolved = {call_id for call_id, _option in self.resolutions}
        return [veto for veto in self.vetoes if veto["callId"] not in resolved]

    def resolve_veto(self, _session_name, call_id, option):
        self.resolutions.append((call_id, option))


class VetoAgentBridgeTest(unittest.TestCase):
    def test_returns_assistant_response_and_observed_turns(self):
        turns = [
            {
                "turnNumber": 1,
                "type": "ASSISTANT_RESPONSE",
                "payload": {"content": "done"},
            }
        ]
        api = FakeVetoApi(turns=turns)
        bridge = VetoAgentBridge(
            base_url="http://localhost:8443",
            username="user",
            password="secret",
            pattern="ds",
            workspace="D:/bridge-test",
            session_name="bridge-test",
            timeout_seconds=0.1,
            poll_seconds=0,
            api=api,
        )

        session = bridge.connect()
        run = bridge.run("complete this", lambda _veto: "EXEC_DECLINE")

        self.assertEqual("session-id", session["id"])
        self.assertEqual("assistant_response", run.completion)
        self.assertEqual("done", run.response)
        self.assertEqual(turns, run.turns)
        self.assertEqual([], run.vetoes)

    def test_resolves_veto_and_reports_terminal_refusal(self):
        veto = {
            "callId": "call-1",
            "toolName": "delete_file",
            "options": ["ACCEPT_EXEC", "EXEC_DECLINE"],
        }
        turns = [
            {
                "turnNumber": 1,
                "type": "TOOL_RESPONSE",
                "payload": {
                    "call_id": "call-1",
                    "content": "REFUSED - declined by the user (EXEC_DECLINE).",
                },
            }
        ]
        api = FakeVetoApi(turns=turns, vetoes=[veto])
        bridge = VetoAgentBridge(
            base_url="http://localhost:8443",
            username="user",
            password="secret",
            pattern="ds",
            workspace="D:/bridge-test",
            session_name="bridge-test",
            timeout_seconds=0.1,
            poll_seconds=0,
            api=api,
        )
        bridge.connect()

        run = bridge.run(
            "delete it",
            lambda _veto: "EXEC_DECLINE",
            terminal_on_refusal=True,
        )

        self.assertEqual([("call-1", "EXEC_DECLINE")], api.resolutions)
        self.assertEqual("hitl_refusal", run.completion)
        self.assertEqual("EXEC_DECLINE", run.vetoes[0]["selectedOption"])


if __name__ == "__main__":
    unittest.main()
