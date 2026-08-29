import tempfile
import unittest
from pathlib import Path

from train import relative_log_path


class TrainingLogPathTest(unittest.TestCase):
    def test_training_output_path_is_relative_to_its_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_file = root / "logs" / "training_log.jsonl"
            model = root / "models" / "screening"

            value = relative_log_path(model, log_file)

            self.assertEqual("../models/screening", value)
            self.assertNotIn(":", value)


if __name__ == "__main__":
    unittest.main()
