import json
import unittest
from collections import Counter

from prepare_screening_data import EVAL_SCENARIOS, TRAIN_SCENARIOS, build_records


class ScreeningDatasetTest(unittest.TestCase):
    def test_holdout_scenarios_do_not_leak_into_training(self):
        train_groups = {scenario.group for scenario in TRAIN_SCENARIOS}
        eval_groups = {scenario.group for scenario in EVAL_SCENARIOS}

        self.assertTrue(train_groups)
        self.assertTrue(eval_groups)
        self.assertEqual(set(), train_groups & eval_groups)

    def test_each_split_is_balanced_by_relevance_and_danger(self):
        for scenarios in (TRAIN_SCENARIOS, EVAL_SCENARIOS):
            records = build_records(scenarios)
            outputs = [json.loads(record["output"]) for record in records]
            relevance_counts = Counter(output["relevance"] for output in outputs)
            danger_counts = Counter(output["danger"] for output in outputs)

            self.assertEqual(1, len(set(relevance_counts.values())))
            self.assertEqual(1, len(set(danger_counts.values())))

    def test_records_match_the_runtime_screening_contract(self):
        record = build_records(TRAIN_SCENARIOS)[0]
        output = json.loads(record["output"])

        self.assertEqual("screening", record["task"])
        self.assertEqual(["relevance", "danger", "reason"], list(output))
        self.assertIn('Active user task: "', record["instruction"])
        self.assertIn("Given the agent's thought:", record["instruction"])
        self.assertIn("Tool risk category:", record["instruction"])
        self.assertIn("Tool call:", record["instruction"])
        self.assertIn("SAFE = read-only", record["instruction"])
        self.assertIn("CRITICAL = irreversible", record["instruction"])
        self.assertEqual("HIGH SAFE", output["reason"])

    def test_remote_network_tools_cover_read_and_authorized_write_semantics(self):
        network_train = [scenario for scenario in TRAIN_SCENARIOS if scenario.risk == "NETWORK"]

        self.assertGreaterEqual(
            sum(scenario.danger == "SAFE" for scenario in network_train),
            4,
            "remote read-only operations must not be learned as dangerous merely because they use MCP",
        )
        self.assertGreaterEqual(
            sum(scenario.danger == "ELEVATED" for scenario in network_train),
            4,
            "authorized external mutations need examples distinct from malicious exfiltration",
        )


if __name__ == "__main__":
    unittest.main()
