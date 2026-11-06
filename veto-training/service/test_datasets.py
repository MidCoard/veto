import json
import tempfile
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

    def test_large_import_is_sharded_and_each_shard_has_bounded_preview(self):
        source = self.root / "large.jsonl"
        source.write_text("".join(json.dumps({"instruction": str(index), "output": "safe"}) + "\n"
                                  for index in range(3)))
        with patch("datasets.SHARD_RECORDS", 2):
            self.store.mutate(self.identifier, "import", dict(datasetRevision=0, split="train"), source)
        page = self.store.browse(self.identifier, {})
        self.assertEqual(2, page["total"])
        self.assertEqual([1, 2], sorted(row["records"] for row in page["rows"]))
        first = next(row for row in page["rows"] if row["records"] == 2)
        preview = self.store.preview(self.identifier, first["id"])
        self.assertEqual(2, len(preview["records"]))
        self.assertEqual("0", preview["records"][0]["instruction"])

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
