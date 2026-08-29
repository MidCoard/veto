import tempfile
import unittest
from pathlib import Path

from convert_to_gguf import paths_refer_to_same_file, relative_log_path


class ConversionPathTest(unittest.TestCase):
    def test_equivalent_deployment_paths_are_not_copied_twice(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            direct = root / "models" / "veto-slm.gguf"
            equivalent = root / "training" / ".." / "models" / "veto-slm.gguf"

            self.assertTrue(paths_refer_to_same_file(direct, equivalent))

    def test_conversion_log_paths_are_relative_and_portable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_file = root / "training" / "conversion_log.jsonl"
            artifact = root / "models" / "veto-slm.gguf"

            value = relative_log_path(artifact, log_file)

            self.assertEqual("../models/veto-slm.gguf", value)
            self.assertNotIn(":", value)


if __name__ == "__main__":
    unittest.main()
