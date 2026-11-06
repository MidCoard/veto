import json
import tempfile
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from training_state import (CheckpointControl, ResumableTrainer, complete_checkpoint,
                            prepare_run, run_signature, validate_checkpoint, worker_lock)


class TrainingStateTest(unittest.TestCase):
    def test_signature_binds_data_model_options_and_original_run_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "base"
            model.mkdir()
            (model / "config.json").write_text("{}")
            (model / "model.safetensors").write_bytes(b"fixture weights")
            data = root / "train.jsonl"
            data.write_text("{}")
            args = SimpleNamespace(base_model=str(model), hf_token=None, output_dir=str(root / "run"),
                data_path=str(data), eval_data_path=None, epochs=2, lr=.001, batch_size=1,
                eval_batch_size=1, gradient_accumulation_steps=2, lora_r=2, lora_alpha=4,
                lora_dropout=.1, max_seq_length=16, prompt_format="raw", checkpoint_steps=2)
            with patch("transformers.utils.hub.cached_file", return_value=str(model / "config.json")), \
                    patch("torch.cuda.is_available", return_value=False), \
                    patch("torch.cuda.device_count", return_value=0):
                original = run_signature(args)
                self.assertIn("model.safetensors", original["model_files"])
                data.write_text("changed")
                self.assertNotEqual(original["training_data"], run_signature(args)["training_data"])
                args.batch_size = 2
                self.assertNotEqual(original["options"], run_signature(args)["options"])
                args.output_dir = str(root / "another-run")
                self.assertNotEqual(original["output_directory"], run_signature(args)["output_directory"])

    def test_worker_lock_excludes_another_process_and_releases_after_failure(self):
        probe = """
import os, sys
with open(sys.argv[1], 'r+b') as stream:
    try:
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.lockf(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
    except OSError:
        sys.exit(23)
"""
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, "-B", "-c", probe, str(Path(directory) / "worker.lock")]
            with self.assertRaisesRegex(RuntimeError, "simulated failure"):
                with worker_lock(directory):
                    self.assertEqual(23, subprocess.run(command, check=False).returncode)
                    raise RuntimeError("simulated failure")
            self.assertEqual(0, subprocess.run(command, check=False).returncode)

    def test_incomplete_corrupt_foreign_and_mismatched_checkpoints_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            checkpoint = output / "checkpoint-2"
            checkpoint.mkdir()
            signature = {"data": "original", "options": {"batch": 1}}
            with self.assertRaisesRegex(ValueError, "completion marker"):
                validate_checkpoint(output, checkpoint, signature)
            with self.assertRaisesRegex(ValueError, "missing training state"):
                complete_checkpoint(checkpoint, signature)
            for name in ("adapter_model.safetensors", "adapter_config.json", "optimizer.pt",
                         "scheduler.pt", "rng_state.pth"):
                (checkpoint / name).write_bytes(b"fixture")
            (checkpoint / "trainer_state.json").write_text(json.dumps({"global_step": 2, "max_steps": 8}))
            complete_checkpoint(checkpoint, signature)
            self.assertEqual(checkpoint, validate_checkpoint(output, checkpoint, signature))
            with self.assertRaisesRegex(ValueError, "does not match"):
                validate_checkpoint(output, checkpoint, {"data": "different"})
            with self.assertRaisesRegex(ValueError, "does not belong"):
                validate_checkpoint(output / "foreign", checkpoint, signature)
            complete_checkpoint(checkpoint, signature, optimization_finished=True)
            with self.assertRaisesRegex(ValueError, "completed optimization"):
                validate_checkpoint(output, checkpoint, signature)
            (checkpoint / "trainer_state.json").write_text(json.dumps({"global_step": 2, "max_steps": 8,
                "stateful_callbacks": {"EarlyStoppingCallback": {
                    "args": {"early_stopping_patience": 1},
                    "attributes": {"early_stopping_patience_counter": 1}}}}))
            self.assertTrue(complete_checkpoint(checkpoint, signature))
            with self.assertRaisesRegex(ValueError, "completed optimization"):
                validate_checkpoint(output, checkpoint, signature)
            (checkpoint / "trainer_state.json").write_text(json.dumps({"global_step": 2, "max_steps": 8}))
            complete_checkpoint(checkpoint, signature)
            (checkpoint / "optimizer.pt").write_bytes(b"interrupted write")
            with self.assertRaisesRegex(ValueError, "modified"):
                validate_checkpoint(output, checkpoint, signature)

    def test_existing_run_requires_explicit_matching_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(output_dir=directory, checkpoint_steps=100, resume_from_checkpoint=None)
            with patch("training_state.run_signature", return_value={"data": "a"}):
                prepare_run(args)
                with self.assertRaisesRegex(ValueError, "already exists"):
                    prepare_run(args)
            args.resume_from_checkpoint = str(Path(directory) / "checkpoint-2")
            with patch("training_state.run_signature", return_value={"data": "b"}):
                with self.assertRaisesRegex(ValueError, "does not match"):
                    prepare_run(args)

    def test_actual_trainer_pause_resume_matches_uninterrupted_optimizer_rng_and_weights(self):
        import torch
        from peft import LoraConfig, get_peft_model
        from transformers import (Qwen2Config, Qwen2ForCausalLM, TrainingArguments,
                                  TrainerCallback, EarlyStoppingCallback, default_data_collator)

        class PauseAtStep(TrainerCallback):
            def __init__(self, step):
                self.step = step

            def on_step_end(self, args, state, control, **kwargs):
                if state.global_step == self.step:
                    (Path(args.output_dir) / "pause.request").write_text("pause")

            def on_evaluate(self, args, state, control, **kwargs):
                if state.global_step == self.step and state.epoch == round(state.epoch):
                    marker = Path(args.output_dir) / f"checkpoint-{self.step}" / "complete.json"
                    if marker.exists():
                        raise AssertionError("Epoch checkpoint marked complete before validation settled")

        records = [{"input_ids": [1, 2, 3 + i, 4, 5, 6, 7, 8],
                    "attention_mask": [1] * 8,
                    "labels": [-100, -100, 3 + i, 4, 5, 6, 7, 8]} for i in range(8)]
        signature = {"fixture": "tiny-qwen-lora"}

        def create(output, pause_at=None):
            torch.manual_seed(17)
            model = get_peft_model(Qwen2ForCausalLM(Qwen2Config(
                vocab_size=32, hidden_size=16, intermediate_size=32, num_hidden_layers=1,
                num_attention_heads=2, num_key_value_heads=2, use_cache=False)), LoraConfig(
                    r=2, lora_alpha=4, lora_dropout=0.1, target_modules="all-linear", task_type="CAUSAL_LM"))
            options = TrainingArguments(output_dir=str(output), use_cpu=True, num_train_epochs=2,
                per_device_train_batch_size=1, per_device_eval_batch_size=1, gradient_accumulation_steps=2,
                learning_rate=0.001, save_strategy="epoch", eval_strategy="epoch", save_total_limit=2,
                load_best_model_at_end=True, metric_for_best_model="eval_loss", greater_is_better=False,
                restore_callback_states_from_checkpoint=True, dataloader_num_workers=0,
                report_to="none", logging_strategy="no", disable_tqdm=True, seed=42)
            control = CheckpointControl(output, signature, 2)
            callbacks = ([PauseAtStep(pause_at)] if pause_at else []) + [control, EarlyStoppingCallback(2)]
            trainer = ResumableTrainer(model=model, args=options, train_dataset=records, eval_dataset=records,
                data_collator=default_data_collator, callbacks=callbacks)
            return trainer, control

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            full, _ = create(root / "full")
            full.train()
            expected_rng = torch.get_rng_state().clone()
            for pause_at in (3, 4, 5):
                partial, control = create(root / f"resumed-{pause_at}", pause_at=pause_at)
                partial.train()
                self.assertEqual(pause_at, partial.state.global_step)
                checkpoint = validate_checkpoint(root / f"resumed-{pause_at}", control.paused_checkpoint, signature)
                if pause_at < 4:
                    self.assertFalse(any("eval_loss" in item for item in partial.state.log_history))
                del partial
                resumed, _ = create(root / f"resumed-{pause_at}")
                resumed.train(resume_from_checkpoint=str(checkpoint))
                self.assertEqual(8, resumed.state.global_step)
                torch.testing.assert_close(torch.get_rng_state(), expected_rng, rtol=0, atol=0)
                for (name, value), (other_name, other) in zip(
                        full.model.named_parameters(), resumed.model.named_parameters(), strict=True):
                    self.assertEqual(name, other_name)
                    torch.testing.assert_close(value, other, rtol=0, atol=0)
                self.assertEqual(full.lr_scheduler.state_dict(), resumed.lr_scheduler.state_dict())
                expected = full.optimizer.state_dict()["state"]
                actual = resumed.optimizer.state_dict()["state"]
                self.assertEqual(expected.keys(), actual.keys())
                for parameter, values in expected.items():
                    for key, value in values.items():
                        torch.testing.assert_close(value, actual[parameter][key], rtol=0, atol=0)



if __name__ == "__main__":
    unittest.main()
