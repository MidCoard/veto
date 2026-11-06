import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from export import adapter_directory, complete_merged, ensure_merged


class ExportRecoveryTest(unittest.TestCase):
    def test_complete_merged_requires_config_and_all_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertFalse(complete_merged(root))
            (root / "config.json").write_text("{}")
            (root / "model.safetensors.index.json").write_text(json.dumps(
                {"weight_map": {"one": "one.safetensors", "two": "two.safetensors"}}))
            (root / "one.safetensors").write_bytes(b"one")
            self.assertFalse(complete_merged(root))
            (root / "two.safetensors").write_bytes(b"two")
            self.assertTrue(complete_merged(root))

    def test_existing_complete_merge_is_reused_without_model_loading(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "merged").mkdir()
            (output / "merged/config.json").write_text("{}")
            (output / "merged/model.safetensors").write_bytes(b"weights")
            with patch("export.merge_adapter") as merge:
                self.assertEqual(output / "merged", ensure_merged("base", output))
                merge.assert_not_called()

    def test_finished_checkpoint_is_an_export_source_not_a_resume_request(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            checkpoint = output / "checkpoint-12"
            checkpoint.mkdir()
            (checkpoint / "adapter_config.json").write_text("{}")
            (checkpoint / "adapter_model.safetensors").write_bytes(b"adapter")
            (checkpoint / "complete.json").write_text(json.dumps(
                {"optimization_finished": True, "files": {"adapter_config.json": "hash"}}))
            state = SimpleNamespace(checkpoint_files=lambda path: {"adapter_config.json": "hash"})
            with patch.dict("sys.modules", {"training_state": state}):
                self.assertEqual(checkpoint, adapter_directory(output))
                with patch("export.merge_adapter") as merge:
                    ensure_merged("base", output)
                    merge.assert_called_once_with("base", output, checkpoint)
                    self.assertFalse((output / "resume.request").exists())


if __name__ == "__main__":
    unittest.main()
