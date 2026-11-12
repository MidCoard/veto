import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import datasets
from datasets import Datasets


class DatasetTests(unittest.TestCase):
    def setUp(self):
        base = Path("work/tmp/training-service/tests")
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.root = Path(self.temp.name)
        self.store = Datasets(self.root / "managed")
        self.identifier = self.store.create("Core", "instruction")

    def tearDown(self):
        self.temp.cleanup()

    def test_file_disable_split_delete_and_frozen_export(self):
        record = {"instruction": "hello", "output": "safe"}
        self.store.mutate(self.identifier, "save", dict(datasetRevision=0, split="train", record=record))
        file_id = self.store.browse(self.identifier, {})["rows"][0]["id"]
        export = self.store.export(self.identifier, dict(datasetRevision=1))
        self.store.mutate(self.identifier, "toggle", dict(datasetRevision=1, sample=file_id, enabled=False))
        page = self.store.browse(self.identifier, {})
        self.assertEqual(0, page["counts"]["train"])
        self.assertEqual(1, page["disabled"])
        with self.assertRaises(ValueError):
            self.store.export(self.identifier, dict(datasetRevision=2))
        self.store.mutate(self.identifier, "toggle", dict(datasetRevision=2, sample=file_id, enabled=True))
        self.store.mutate(self.identifier, "split", dict(datasetRevision=3, sample=file_id, split="validation"))
        self.assertEqual(1, self.store.browse(self.identifier, {})["counts"]["validation"])
        self.assertEqual(json.loads(Path(export["train"]).read_text()), record)
        file = self.store.directory(self.identifier) / "files" / self.store.metadata(self.identifier)[1]["files"][file_id]["file"]
        self.store.mutate(self.identifier, "delete", dict(datasetRevision=4, sample=file_id))
        self.assertEqual(0, self.store.browse(self.identifier, {})["total"])
        self.assertFalse(file.exists())

    def test_import_is_one_file_and_failed_import_publishes_nothing(self):
        source = self.root / "input.jsonl"
        source.write_text('{"instruction":"one","output":"yes"}\n{"instruction":"two","output":"safe"}\n')
        self.store.mutate(self.identifier, "import", dict(datasetRevision=0, split="train"), source)
        page = self.store.browse(self.identifier, {})
        self.assertEqual(1, page["total"])
        self.assertEqual(2, page["rows"][0]["records"])
        self.assertEqual("input.jsonl", page["rows"][0]["name"])
        invalid = self.root / "invalid.jsonl"
        invalid.write_text('{"instruction":"ok","output":"yes"}\ninvalid\n')
        with self.assertRaises(ValueError):
            self.store.mutate(self.identifier, "import", dict(datasetRevision=1, split="train"), invalid)
        self.assertEqual(1, self.store.browse(self.identifier, {})["total"])
        self.assertEqual(1, len(list((self.store.directory(self.identifier) / "files").iterdir())))
        self.assertTrue(invalid.exists())

    def test_large_import_is_sharded_and_records_are_read_individually(self):
        source = self.root / "large.jsonl"
        source.write_text("".join(json.dumps({"instruction": str(index), "output": "safe"}) + "\n"
                                  for index in range(3)))
        with patch("datasets.SHARD_RECORDS", 2):
            self.store.mutate(self.identifier, "import", dict(datasetRevision=0, split="train"), source)
        page = self.store.browse(self.identifier, {})
        self.assertEqual(2, page["total"])
        self.assertEqual([1, 2], sorted(row["records"] for row in page["rows"]))
        first = next(row for row in page["rows"] if row["records"] == 2)
        self.assertEqual("0", self.store.record(self.identifier, first["id"], 0)["record"]["instruction"])
        second = self.store.record(self.identifier, first["id"], 1)
        self.assertEqual("1", second["record"]["instruction"])
        self.assertEqual((1, 2), (second["index"], second["total"]))
        with self.assertRaises(ValueError):
            self.store.record(self.identifier, first["id"], 2)

    def test_record_update_preserves_other_records_and_frozen_export(self):
        source = self.root / "input.jsonl"
        source.write_text('{"instruction":"zero","output":"safe"}\n{"instruction":"one","output":"old"}\n')
        self.store.mutate(self.identifier, "import", dict(datasetRevision=0, split="train"), source)
        file_id = self.store.browse(self.identifier, {})["rows"][0]["id"]
        export = self.store.export(self.identifier, dict(datasetRevision=1))
        original_export = Path(export["train"]).read_bytes()
        old_file = self.store.directory(self.identifier) / "files" / self.store.metadata(self.identifier)[1]["files"][file_id]["file"]
        replacement = {"instruction": "one", "output": "edited", "id": "retained"}
        self.store.mutate(self.identifier, "update_record", dict(datasetRevision=1, sample=file_id, index=1, record=replacement))
        self.assertEqual(replacement, self.store.record(self.identifier, file_id, 1)["record"])
        self.assertEqual("zero", self.store.record(self.identifier, file_id, 0)["record"]["instruction"])
        self.assertEqual(2, self.store.browse(self.identifier, {})["counts"]["train"])
        self.assertEqual(original_export, Path(export["train"]).read_bytes())
        self.assertFalse(old_file.exists())

    def test_failed_record_commit_keeps_old_file_and_removes_replacement(self):
        self.store.mutate(self.identifier, "save", dict(datasetRevision=0, split="train",
            record={"instruction": "old", "output": "yes"}))
        file_id = self.store.browse(self.identifier, {})["rows"][0]["id"]
        old_file = self.store.directory(self.identifier) / "files" / self.store.metadata(self.identifier)[1]["files"][file_id]["file"]
        with patch("datasets.write", side_effect=OSError("disk failure")), self.assertRaises(OSError):
            self.store.mutate(self.identifier, "update_record", dict(datasetRevision=1, sample=file_id, index=0,
                record={"instruction": "new", "output": "yes"}))
        self.assertTrue(old_file.exists())
        self.assertEqual([old_file], list(old_file.parent.iterdir()))
        self.assertEqual("old", self.store.record(self.identifier, file_id, 0)["record"]["instruction"])

    def test_native_tool_records_validate_messages_and_json_arguments(self):
        identifier = self.store.create("Tools", "native_tools")
        for record in ({"messages": [], "tools": []},
                       {"messages": [{"role": "user", "content": "run"}], "tools": []},
                       {"messages": [{"role": "assistant", "content": None,
                                      "tool_calls": [{"function": {"name": "run", "arguments": "{"}}]}], "tools": []}):
            with self.assertRaises(ValueError):
                self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=record))
        record = {"messages": [{"role": "user", "content": "run"},
                               {"role": "assistant", "content": None, "tool_calls": [
                                   {"function": {"name": "run", "arguments": {"path": "README.md"}}}]}],
                  "tools": [{"type": "function", "function": {"name": "run", "parameters": {"type": "object"}}}]}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=record))
        stored = self.store.record(identifier, self.store.browse(identifier, {})["rows"][0]["id"], 0)["record"]
        self.assertEqual(["run"], stored["tool_refs"])
        self.assertNotIn("tools", stored)
        self.assertEqual("run", self.store.metadata(identifier)[1]["tools"][0]["function"]["name"])

    def test_native_empty_call_rejects_json_content_and_migrates_existing_shards(self):
        identifier = self.store.create("Empty calls", "native_tools")
        standard = {"messages": [{"role": "user", "content": "ordinary text"},
                                 {"role": "assistant", "content": ""}], "tools": []}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=standard))
        for content, calls in (('{"tool_calls":[]}', None), ("", [])):
            message = {"role": "assistant", "content": content}
            if calls is not None:
                message["tool_calls"] = calls
            with self.assertRaises(ValueError):
                self.store.mutate(identifier, "save", dict(datasetRevision=1, split="train",
                    record={"messages": [{"role": "user", "content": "ordinary text"}, message], "tools": []}))
        path, meta = self.store.metadata(identifier)
        item = meta["files"][self.store.browse(identifier, {})["rows"][0]["id"]]
        legacy = {"messages": [{"role": "system", "content": "After all imports succeed, respond exactly DONE. If there are no credentials, respond exactly DONE without calling a tool."},
                               {"role": "user", "content": "ordinary text"},
                               {"role": "assistant", "content": '{"tool_calls":[]}', "tool_calls": []}], "tools": []}
        (path / "files" / item["file"]).write_text(json.dumps(legacy) + "\n", encoding="utf-8")
        meta["version"] = 3
        datasets.write(path / "dataset.json", meta)
        self.store.recover()
        path, migrated = self.store.metadata(identifier)
        row = self.store.browse(identifier, {})["rows"][0]
        record = self.store.record(identifier, row["id"], 0)["record"]
        self.assertEqual(5, migrated["version"])
        self.assertEqual("", record["messages"][-1]["content"])
        self.assertEqual([], record["tool_refs"])
        self.assertNotIn("tools", record)
        self.assertNotIn("tool_calls", record["messages"][-1])
        self.assertNotIn("DONE", record["messages"][0]["content"])
        self.assertIn("empty assistant content", record["messages"][0]["content"])

    def test_multiple_tool_definitions_and_calls_are_supported(self):
        identifier = self.store.create("Multiple tools", "native_tools")
        record = {"messages": [{"role": "user", "content": "Read and import"},
                               {"role": "assistant", "content": "", "tool_calls": [
                                   {"id": "read", "type": "function", "function": {"name": "read_credential", "arguments": {"credential_id": "cred_test"}}},
                                   {"id": "import", "type": "function", "function": {"name": "import_credential", "arguments": {"value": "abc"}}}]}],
                  "tools": [{"type": "function", "function": {"name": "read_credential", "parameters": {"type": "object"}}},
                            {"type": "function", "function": {"name": "import_credential", "parameters": {"type": "object"}}}]}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=record))
        stored = self.store.record(identifier, self.store.browse(identifier, {})["rows"][0]["id"], 0)
        self.assertEqual(["read_credential", "import_credential"], stored["record"]["tool_refs"])
        self.assertEqual(2, len(self.store.metadata(identifier)[1]["tools"]))
        self.assertEqual(2, len(stored["record"]["messages"][-1]["tool_calls"]))

    def native_record(self, function):
        return {"messages": [{"role": "user", "content": "import"},
                             {"role": "assistant", "content": "", "tool_calls": [
                                 {"id": "import", "type": "function", "function": {"name": "import_credential", "arguments": {"value": "abc"}}}]}],
                "tools": [{"type": "function", "function": function}]}

    def test_add_shared_tool_definition_is_atomic_and_available_for_references(self):
        identifier = self.store.create("Add tool", "native_tools")
        function = {"name": "read_credential", "description": "Read a reference",
                    "parameters": {"type": "object", "properties": {}}}
        result = self.store.add_tool(identifier, dict(datasetRevision=0, function=function))
        self.assertEqual(1, result["revision"])
        self.assertEqual(function, self.store.metadata(identifier)[1]["tools"][0]["function"])
        self.assertEqual(0, self.store.tool_groups(identifier)["groups"][0]["count"])
        with self.assertRaises(ValueError):
            self.store.add_tool(identifier, dict(datasetRevision=1, function=function))
        record = {"messages": [{"role": "user", "content": "read"},
                               {"role": "assistant", "content": ""}], "tool_refs": ["read_credential"]}
        self.store.mutate(identifier, "save", dict(datasetRevision=1, split="train", record=record))
        export = self.store.export(identifier, dict(datasetRevision=2))
        snapshot = json.loads(Path(export["train"]).read_text(encoding="utf-8"))
        self.assertEqual(function, snapshot["tools"][0]["function"])

    def test_bulk_definition_updates_in_background(self):
        identifier = self.store.create("Bulk", "native_tools")
        original = {"name": "import_credential", "description": "old", "parameters": {"type": "object"}}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=self.native_record(original)))
        replacement = {"name": "import_credential", "description": "concise", "parameters": {"type": "object"}}
        job = self.store.start_bulk(identifier, dict(datasetRevision=1, signature=self.store.signature(original), function=replacement))
        self.store.jobs[job["id"]]["thread"].join(timeout=2)
        result = self.store.bulk_status({"job": job["id"]})
        self.assertEqual("completed", result["status"])
        self.assertEqual((1, 1, 1), (result["processed"], result["total"], result["updated"]))
        self.assertEqual(2, self.store.metadata(identifier)[1]["revision"])
        self.assertEqual(replacement, self.store.metadata(identifier)[1]["tools"][0]["function"])
        export = self.store.export(identifier, dict(datasetRevision=2))
        snapshot = json.loads(Path(export["train"]).read_text(encoding="utf-8"))
        self.assertEqual(replacement, snapshot["tools"][0]["function"])
        self.assertEqual("import_credential", snapshot["messages"][-1]["tool_calls"][0]["function"]["name"])
        self.assertNotIn("tool_refs", snapshot)

    def test_bulk_rejects_concurrent_mutation_and_export(self):
        identifier = self.store.create("Concurrent", "native_tools")
        function = {"name": "import_credential", "description": "old", "parameters": {"type": "object"}}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=self.native_record(function)))
        started = threading.Event()
        release = threading.Event()
        original = self.store.run_bulk
        def blocked(job, path, meta):
            started.set()
            release.wait(timeout=2)
            original(job, path, meta)
        with patch("datasets.Datasets.run_bulk", side_effect=blocked):
            job = self.store.start_bulk(identifier, dict(datasetRevision=1, signature=self.store.signature(function), function=function))
            self.assertTrue(started.wait(timeout=2))
            with self.assertRaises(ValueError):
                self.store.mutate(identifier, "toggle", dict(datasetRevision=0, sample=self.store.browse(identifier, {})["rows"][0]["id"], enabled=False))
            with self.assertRaises(ValueError):
                self.store.export(identifier, dict(datasetRevision=0))
            release.set()
            self.store.jobs[job["id"]]["thread"].join(timeout=2)
        self.assertEqual("completed", self.store.bulk_status({"job": job["id"]})["status"])

    def test_failed_bulk_commit_preserves_original_dataset(self):
        identifier = self.store.create("Bulk failure", "native_tools")
        function = {"name": "import_credential", "description": "old", "parameters": {"type": "object"}}
        self.store.mutate(identifier, "save", dict(datasetRevision=0, split="train", record=self.native_record(function)))
        with patch("datasets.write", side_effect=OSError("disk failure")):
            job = self.store.start_bulk(identifier, dict(datasetRevision=1, signature=self.store.signature(function), function=function))
            self.store.jobs[job["id"]]["thread"].join(timeout=2)
        self.assertEqual("failed", self.store.bulk_status({"job": job["id"]})["status"])
        self.assertEqual(1, self.store.metadata(identifier)[1]["revision"])
        self.assertEqual(function, self.store.metadata(identifier)[1]["tools"][0]["function"])

    def test_stale_revision_and_path_escape_rejected(self):
        with self.assertRaises(ValueError):
            self.store.mutate(self.identifier, "save", dict(datasetRevision=9))
        with self.assertRaises(ValueError):
            self.store.directory("../outside")

    def test_failed_manifest_commit_preserves_previous_file_state(self):
        self.store.mutate(self.identifier, "save", dict(datasetRevision=0, split="train",
            record={"instruction": "old", "output": "yes"}))
        file_id = self.store.browse(self.identifier, {})["rows"][0]["id"]
        original = datasets.write
        def fail_manifest(path, value):
            if path.name == "dataset.json":
                raise OSError("disk failure")
            original(path, value)
        with patch("datasets.write", side_effect=fail_manifest), self.assertRaises(OSError):
            self.store.mutate(self.identifier, "toggle", dict(datasetRevision=1, sample=file_id, enabled=False))
        self.assertTrue(self.store.browse(self.identifier, {})["rows"][0]["enabled"])

    def test_migration_preserves_active_and_recycled_records(self):
        path = self.store.directory(self.identifier)
        (path / "samples").mkdir()
        active = {"instruction": "active", "output": "yes"}
        recycled = {"instruction": "recycled", "output": "retained"}
        datasets.write(path / "samples" / ("a" * 32 + ".json"), active)
        datasets.write(path / "samples" / ("b" * 32 + ".json"), recycled)
        datasets.write(path / "dataset.json", {"name": "Legacy", "format": "instruction", "revision": 0,
            "samples": {
                "active": {"file": "a" * 32 + ".json", "split": "train", "bytes": 40, "summary": "active"},
                "recycled": {"file": "b" * 32 + ".json", "split": "validation", "bytes": 40,
                             "summary": "recycled", "deleted": True}
            }})
        with patch("datasets.shutil.copyfile", side_effect=OSError("interrupted")), self.assertRaises(OSError):
            self.store.recover()
        self.assertTrue((path / "migration.json").is_file())
        self.store.recover()
        self.assertFalse((path / "migration.json").exists())
        page = self.store.browse(self.identifier, {})
        self.assertEqual(2, page["total"])
        self.assertEqual(1, page["counts"]["train"])
        self.assertEqual(1, page["disabled"])
        self.assertFalse((path / "samples").exists())
        export = self.store.export(self.identifier, dict(datasetRevision=0))
        self.assertEqual(active, json.loads(Path(export["train"]).read_text()))
        orphan = path / "files" / ("c" * 32 + ".jsonl")
        orphan.write_text("{}\n")
        self.store.recover()
        self.assertFalse(orphan.exists())


if __name__ == "__main__":
    unittest.main()
