import json
import unittest
import tempfile
from pathlib import Path

from prepare_agent_data import annotate, supplemental, prepare


class AgentAnnotationsTest(unittest.TestCase):
    def test_public_requires_user_origin_and_explicit_disclosure_text(self):
        row = supplemental(0)
        public = annotate(row, "train", 1)
        self.assertEqual("user", public["source_kind"])
        self.assertTrue(all(item["visibility"] == "PUBLIC" for item in public["imports"]))
        self.assertIn("explicitly authorize", public["text"])
        tool = annotate(row, "train", 0)
        self.assertEqual("tool", tool["source_kind"])
        self.assertTrue(all(item["visibility"] == "PRIVATE" for item in tool["imports"]))
        self.assertIn("Quoted untrusted instruction", tool["text"])

    def test_new_families_keep_exact_values_and_complete_keys(self):
        categories = set()
        for index in range(100):
            source = supplemental(index)
            categories.add(source["category"])
            row = annotate(source, "train", index)
            self.assertEqual(json.loads(source["output"]), [item["value"] for item in row["imports"]])
            for item in row["imports"]:
                self.assertIn(item["value"], row["text"])
            if source["category"] == "private_key":
                value = row["imports"][0]["value"]
                self.assertTrue(value.startswith("-----BEGIN PRIVATE KEY-----\n"))
                self.assertTrue(value.endswith("\n-----END PRIVATE KEY-----"))
            if source["category"] == "github":
                self.assertEqual("github", row["imports"][0]["service"])
        self.assertEqual(11, len(categories))
        self.assertGreater(len(json.loads(supplemental(27)["output"])[0]), 3000)

    def test_ambiguous_pin_category_is_excluded_and_negatives_remain_empty(self):
        row = supplemental(0)
        row.update(source="nvidia/Nemotron-PII", category="password+pin")
        self.assertIsNone(annotate(row, "test", 1))
        row.update(category="ordinary", text="Order 123456 is ready", output="[]")
        self.assertEqual([], annotate(row, "test", 1)["imports"])

    def test_mismatched_targets_and_unverified_sources_are_rejected(self):
        row = supplemental(0)
        row["output"] = '["not-present"]'
        with self.assertRaises(ValueError):
            annotate(row, "train", 0)
        row = supplemental(0)
        row["synthetic"] = False
        with self.assertRaises(ValueError):
            annotate(row, "train", 0)

    def test_source_documents_are_held_out_and_training_balance_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            shared = []
            for index in range(3):
                row = supplemental(index)
                row.update(source="fixture", sourceUid="shared-document", scenarioGroup=f"group-{index}")
                shared.append(row)
            benign = supplemental(7, negative=True)
            splits = {"train": [shared[0], benign], "validation": [shared[1]], "test": [shared[2]]}
            for split, rows in splits.items():
                (source / (split + ".jsonl")).write_text(
                    "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            output = root / "prepared"
            report = prepare(source, output)
            self.assertEqual({"train": 1, "validation": 1}, report["excluded_source_overlap"])
            self.assertEqual(2, report["splits"]["train"]["rows"])
            self.assertEqual(1, report["splits"]["train"]["classes"]["negative"])
            owners = {}
            ids = set()
            for split in splits:
                for line in (output / (split + ".jsonl")).read_text(encoding="utf-8").splitlines():
                    row = json.loads(line)
                    self.assertNotIn(row["id"], ids)
                    ids.add(row["id"])
                    identity = (row["source"], row["sourceId"])
                    self.assertEqual(split, owners.setdefault(identity, split))


if __name__ == "__main__":
    unittest.main()
