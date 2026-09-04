import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from convert_to_gguf import main, paths_refer_to_same_file, relative_log_path


class ConversionPathTest(unittest.TestCase):
    def test_managed_conversion_does_not_deploy_to_default_models(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "merged"
            model.mkdir()
            output = root / "gguf"
            output.mkdir()
            (output / "veto-slm-q4_k_m.gguf").write_bytes(b"new model")
            arguments = [
                "convert_to_gguf.py", "--model-dir", str(model),
                "--output-dir", str(output), "--no-default-copy",
                "--log-file", str(root / "conversion.jsonl"),
            ]
            with patch("sys.argv", arguments), patch(
                "convert_to_gguf.find_llama_cpp_scripts", return_value=None
            ), patch("convert_to_gguf.shutil.copy2") as copy:
                main()
            copy.assert_not_called()

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
