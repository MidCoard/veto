import unittest

from evaluate_agent import IMPORT, evaluate


def call(value="secret", visibility="PRIVATE"):
    return {"function": {"name": IMPORT, "arguments": {
        "value": value, "visibility": visibility, "service": "database", "label": "Login"}}}


class EvaluationTest(unittest.TestCase):
    def score(self, expected, actual, completed=True):
        return evaluate([{"id": "one", "messages": [{"tool_calls": expected}]}],
                        [{"id": "one", "tool_calls": actual, "completed": completed}])

    def test_exact_and_no_secret_completion(self):
        self.assertEqual(1, self.score([call()], [call()])["exactWorkflowRate"])
        self.assertEqual(1, self.score([], [])["exactWorkflowRate"])
        self.assertEqual(0, self.score([], [], False)["exactWorkflowRate"])

    def test_wrong_visibility_is_not_success(self):
        result = self.score([call()], [call(visibility="PUBLIC")])
        self.assertEqual(1, result["exactValues"]["recall"])
        self.assertEqual(0, result["exactWorkflowRate"])
        self.assertEqual(1, result["falsePublicImports"])
        self.assertEqual(1, result["importArguments"]["falseNegatives"])

    def test_duplicates_hallucinations_and_missed_values(self):
        result = self.score([call(), call("missed")], [call(), call(), call("invented")])
        self.assertEqual(1, result["exactValues"]["truePositives"])
        self.assertEqual(2, result["exactValues"]["falsePositives"])
        self.assertEqual(1, result["exactValues"]["falseNegatives"])

    def test_invalid_call_is_not_a_benign_success(self):
        result = self.score([], [{"function": {"name": "unknown"}}])
        self.assertEqual(1, result["invalidCalls"])
        self.assertEqual(1, result["importArguments"]["falsePositives"])
        self.assertEqual(1, result["benignFalseAlarmRate"])
        self.assertEqual(0, result["exactWorkflowRate"])

    def test_traces_cannot_be_missing_reordered_or_duplicated(self):
        gold = [{"id": "one", "messages": []}]
        with self.assertRaises(ValueError):
            evaluate(gold, [])
        with self.assertRaises(ValueError):
            evaluate(gold, [{"id": "other", "tool_calls": [], "completed": True}])
        with self.assertRaises(ValueError):
            evaluate(gold * 2, [{"id": "one", "tool_calls": [], "completed": True}] * 2)


if __name__ == "__main__":
    unittest.main()
