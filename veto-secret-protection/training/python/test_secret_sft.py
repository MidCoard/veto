import json
import unittest

from evaluate_secret_hf import metrics
from prepare_secret_data import BENIGN, TEMPLATES, build_split
from validate_secret_data import validate_record


class SecretSftTest(unittest.TestCase):
    def test_validator_rejects_non_synthetic_and_invented_values(self):
        row = {"id": "one", "task": "secret_extraction", "synthetic": True,
               "text": "password alpha", "instruction": "Extract: password alpha",
               "output": '["alpha"]'}
        validate_record(row)
        validate_record({**row, "output": "[]"})
        for invalid in ({"synthetic": False}, {"output": '["invented"]'},
                        {"output": '{}'}, {"output": '[1]'}, {"output": '["alp"]'},
                        {"instruction": ""}, {"task": "screening"}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_record({**row, **invalid})

    def test_splits_have_disjoint_templates_values_and_inputs(self):
        seen_templates, seen_values, seen_texts = set(), set(), set()
        for split in ("train", "validation", "test"):
            templates = set(TEMPLATES[split] + BENIGN[split])
            records = build_split(split, 3)
            texts = {r["text"] for r in records}
            values = {v for r in records for v in json.loads(r["output"])}
            self.assertFalse(templates & seen_templates)
            self.assertFalse(values & seen_values)
            self.assertFalse(texts & seen_texts)
            self.assertEqual(len(records), len(texts))
            self.assertTrue(any(not json.loads(r["output"]) for r in records))
            for row in records:
                self.assertTrue(row["synthetic"])
                self.assertTrue(all(v in row["text"] for v in json.loads(row["output"])))
            seen_templates |= templates
            seen_values |= values
            seen_texts |= texts

    def test_metrics_count_hallucinations_invalid_json_and_partial_extraction(self):
        records = [
            {"id": "one", "text": "alpha beta", "output": '["alpha","beta"]', "ruleValues": ["alpha"]},
            {"id": "two", "text": "public", "output": '[]', "ruleValues": []},
        ]
        result = metrics(records, ['["alpha", "invented"]', 'not json'])
        self.assertEqual(1, result["modelOnly"]["falsePositives"])
        self.assertEqual(1, result["modelOnly"]["falseNegatives"])
        self.assertEqual(0, result["exactMatch"])
        self.assertEqual(0.5, result["validJsonRate"])
        self.assertEqual(0, result["benignFalseAlarmRate"])
        self.assertEqual(0, result["rulesPlusValidatedValues"]["falsePositives"])


if __name__ == "__main__":
    unittest.main()
