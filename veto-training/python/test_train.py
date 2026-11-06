import tempfile
import unittest
from pathlib import Path

from train import relative_log_path, tokenize_function, select_supervised_logits


class TrainingLogPathTest(unittest.TestCase):
    def test_selected_logits_preserve_qwen_lora_loss_and_gradients(self):
        import torch
        from transformers import Qwen2Config, Qwen2ForCausalLM
        from peft import LoraConfig, get_peft_model

        torch.manual_seed(42)
        model = get_peft_model(Qwen2ForCausalLM(Qwen2Config(
            vocab_size=64, hidden_size=16, intermediate_size=32,
            num_hidden_layers=1, num_attention_heads=2, num_key_value_heads=2,
            attention_dropout=0.0, use_cache=False)), LoraConfig(
                r=2, lora_alpha=4, lora_dropout=0, target_modules="all-linear",
                task_type="CAUSAL_LM"))
        batch = {
            "input_ids": torch.tensor([[1, 2, 3, 4, 5, 6, 7], [8, 9, 10, 11, 12, 0, 0]]),
            "attention_mask": torch.tensor([[1, 1, 1, 1, 1, 1, 1], [1, 1, 1, 1, 1, 0, 0]]),
            "labels": torch.tensor([[-100, -100, 3, 4, -100, -100, 7],
                                    [-100, -100, -100, 11, 12, -100, -100]]),
        }
        selected = select_supervised_logits(batch)
        self.assertEqual([1, 2, 3, 5], selected["logits_to_keep"].tolist())
        self.assertTrue(torch.equal(batch["input_ids"], selected["input_ids"]))
        self.assertTrue(torch.equal(batch["labels"], selected["labels"]))
        # Include Trainer's accumulated-token denominator as well as ordinary mean loss.
        for denominator in (None, torch.tensor(23)):
            with self.subTest(denominator=denominator):
                kwargs = {} if denominator is None else {"num_items_in_batch": denominator}
                model.zero_grad(set_to_none=True)
                full = model(**batch, **kwargs)
                full.loss.backward()
                gradients = {name: parameter.grad.clone() for name, parameter in model.named_parameters()
                             if parameter.grad is not None}
                model.zero_grad(set_to_none=True)
                sparse = model(**selected, **kwargs)
                self.assertEqual((2, 4, 64), tuple(sparse.logits.shape))
                torch.testing.assert_close(full.loss, sparse.loss)
                sparse.loss.backward()
                for name, parameter in model.named_parameters():
                    if name in gradients:
                        torch.testing.assert_close(parameter.grad, gradients[name], atol=1e-6, rtol=1e-5)
                self.assertTrue(any(torch.count_nonzero(value).item() for value in gradients.values()))

    def test_selected_logits_reject_targets_without_a_causal_predecessor(self):
        import torch
        for labels in (torch.tensor([[-100, -100]]), torch.tensor([[5, -100]])):
            with self.assertRaisesRegex(ValueError, "No next-token training target"):
                select_supervised_logits({"labels": labels})

    def test_native_tools_supervise_calls_and_final_reply_only(self):
        class Tokenizer:
            pad_token_id = 0

            def apply_chat_template(self, messages, tools, tokenize, add_generation_prompt):
                self.assert_tools = tools
                return "".join(m["role"] + ":" + m["content"] + "!" for m in messages) + (
                    "assistant:" if add_generation_prompt else "")

            def encode(self, text, add_special_tokens):
                return list(map(ord, text))

        messages = [{"role": "system", "content": "policy"},
                    {"role": "user", "content": "password"},
                    {"role": "assistant", "content": "import(password)"},
                    {"role": "tool", "content": "cred_reference"},
                    {"role": "assistant", "content": "DONE"}]
        batch = {"messages": [messages], "tools": [[{"type": "function"}]]}
        tokenizer = Tokenizer()
        result = tokenize_function(batch, tokenizer, 256, "native_tools")
        target = "".join(chr(value) for value in result["labels"][0] if value != -100)
        self.assertEqual("import(password)!DONE!", target)
        self.assertEqual(batch["tools"][0], tokenizer.assert_tools)
        self.assertLess(len(result["labels"][0]), 256)
        self.assertEqual(len(result["input_ids"][0]), len(result["labels"][0]))
        with self.assertRaisesRegex(ValueError, "refusing target truncation"):
            tokenize_function(batch, tokenizer, 20, "native_tools")

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
