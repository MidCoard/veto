import tempfile
import unittest
from pathlib import Path

from train import relative_log_path, tokenize_function


class TrainingLogPathTest(unittest.TestCase):
    def test_raw_prompts_mask_input_and_padding_but_train_output_and_eos(self):
        class Tokenizer:
            eos_token = "!"

            def __call__(self, texts, truncation=False, padding=False, max_length=None, **kwargs):
                ids = [[ord(c) for c in text] for text in texts]
                masks = [[1] * len(row) for row in ids]
                if padding == "max_length":
                    for row, mask in zip(ids, masks):
                        mask.extend([0] * (max_length - len(row)))
                        row.extend([0] * (max_length - len(row)))
                return {"input_ids": ids, "attention_mask": masks}

        result = tokenize_function({"instruction": ["prompt\n"], "output": ['[]']}, Tokenizer(), 12, "raw")
        self.assertEqual([-100] * 7 + [ord('['), ord(']'), ord('!')] + [-100] * 2, result["labels"][0])
        with self.assertRaisesRegex(ValueError, "refusing to truncate"):
            tokenize_function({"instruction": ["prompt\n"], "output": ['[]']}, Tokenizer(), 8, "raw")

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
