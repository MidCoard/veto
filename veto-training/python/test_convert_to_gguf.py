import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from convert_to_gguf import main, quantizer, relative_log_path


class ConversionTest(unittest.TestCase):
    def test_missing_quantizer_cannot_mislabel_f16_as_q4(self):
        with tempfile.TemporaryDirectory() as directory, patch("shutil.which", return_value=None):
            with self.assertRaises(FileNotFoundError):
                quantizer(Path(directory))

    def test_failure_cannot_report_success_or_overwrite_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "merged"
            model.mkdir()
            (root / "convert_hf_to_gguf.py").touch()
            (root / "llama-quantize.exe").touch()
            output = root / "gguf"
            report = root / "conversion.jsonl"
            args = ["convert", "--model-dir", str(model), "--output-dir", str(output),
                    "--llama-cpp-dir", str(root), "--model-name", "secret", "--log-file", str(report)]
            with patch("sys.argv", args), patch("subprocess.run", side_effect=subprocess.CalledProcessError(2, "converter")):
                with self.assertRaises(subprocess.CalledProcessError):
                    main()
            self.assertFalse(report.exists())
            existing = output / "secret-q4_k_m.gguf"
            existing.write_bytes(b"retained")
            with patch("sys.argv", args), patch("subprocess.run") as run:
                with self.assertRaises(FileExistsError):
                    main()
                run.assert_not_called()
            self.assertEqual(b"retained", existing.read_bytes())

    def test_success_exports_only_to_requested_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "merged").mkdir()
            (root / "convert_hf_to_gguf.py").touch()
            (root / "llama-quantize.exe").touch()
            def run(command, check):
                target = command[command.index("--outfile") + 1] if "--outfile" in command else command[2]
                Path(target).write_bytes(b"GGUF-test")
            args = ["convert", "--model-dir", str(root / "merged"), "--output-dir", str(root / "output"),
                    "--llama-cpp-dir", str(root), "--model-name", "screening", "--log-file", str(root / "result.jsonl")]
            with patch("sys.argv", args), patch("subprocess.run", side_effect=run):
                main()
            self.assertTrue((root / "output/screening-q4_k_m.gguf").exists())
            self.assertFalse((root / "models").exists())
            self.assertIn('"status": "completed"', (root / "result.jsonl").read_text())

    def test_report_paths_are_relative(self):
        self.assertEqual("../models/model.gguf", relative_log_path("models/model.gguf", "logs/run.jsonl"))


if __name__ == "__main__":
    unittest.main()
