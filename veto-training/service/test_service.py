import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer

from budget import Budget
from server import Manager, checkpoints, handler
from processes import Child
from profiles import DEFAULTS, InvalidProfile, validate


class FakeChild:
    def __init__(self, *args, **kwargs):
        self.code = None
        self.closed = False
        self.args = args
        self.options = kwargs
        self.progress = kwargs.get("on_line", lambda line: None)

    def poll(self):
        return self.code

    def close(self):
        self.closed = True


class ServiceTest(unittest.TestCase):
    def setUp(self):
        base = Path(os.environ.get("VETO_TEST_TMP", "work/tmp/training-service/tests"))
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=base)
        self.root = Path(self.temporary.name).resolve()
        self.profile = self.root / "profile.properties"
        settings = self.custom_settings()
        model = Path(settings["base.model"])
        (model / "tokenizer.json").write_text('{}')
        (model / "model.safetensors").write_bytes(b'fixture')
        (self.root / "unused").touch()
        converter = self.root / "fake-llama"
        converter.mkdir()
        (converter / "convert_hf_to_gguf.py").touch()
        (converter / "llama-quantize.exe").touch()
        values = dict(settings, **{"output.directory":"models/old", "python":"unused", "llama.cpp":"fake-llama"})
        self.profile.write_text("\n".join(f"{k}={str(v).replace(chr(92), chr(47))}" for k,v in values.items()), encoding="utf-8")
        self.config = {"repository": str(self.root), "stateDirectory": str(self.root / "state"),
            "java": "unused", "trainingJar": "unused", "volumes": [str(self.root)],
            "reserveBytes": 0, "stateLimitBytes": 1_000_000, "modelLimitBytes": 1_000_000,
            "backendUrl": "http://127.0.0.1:8443", "profiles": [
                {"id": "secret", "name": "Secret", "scope": "fixture only", "properties": "profile.properties"}]}
        self.manager = Manager(self.config, FakeChild)

    def tearDown(self):
        self.manager.close()
        self.temporary.cleanup()

    def action(self, route, **values):
        return self.manager.action(route.split("/"), {"revision": self.manager.state["revision"], **values})

    def test_all_training_purposes_check_files_before_launch(self):
        (self.root / "base-model/model.safetensors").unlink()
        with self.assertRaisesRegex(ValueError, "权重"):
            self.start()
        self.assertIsNone(self.manager.worker)

    def test_upload_and_direct_dataset_recipe(self):
        import io
        result = self.action("datasets/create", name="Manual", format="native_tools")
        identifier = result["id"]
        record = b'{"messages":[],"tools":[]}\n'
        for revision, split in enumerate(("train", "validation")):
            self.manager.upload_dataset(identifier, {"datasetRevision":[str(revision)], "split":[split]}, len(record), io.BytesIO(record))
        settings = self.custom_settings()
        settings["training.data"] = settings["validation.data"] = ""
        self.action("profiles/create", name="From dataset", settings=settings, dataset=identifier, datasetRevision=2)
        profile = next(p for p in self.manager.profiles.values() if p["name"] == "From dataset")
        self.assertIn("exports", self.manager.profile_settings(profile)["training.data"])
        self.assertIsNone(self.manager.worker)

    def test_download_pause_resume_and_register(self):
        row = self.action("downloads/create", repo="org/model", revisionName="main", token="hf_testing")
        worker = self.manager.downloads.worker
        self.assertEqual("hf_testing", worker.args[2]["HF_TOKEN"])
        self.assertNotIn("hf_testing", self.manager.downloads.path.read_text())
        self.action(f"downloads/{row['id']}/pause")
        self.assertTrue(worker.closed)
        self.action(f"downloads/{row['id']}/resume")
        model = Path(row["path"])
        model.mkdir(parents=True)
        (model / "config.json").write_text('{}')
        self.manager.downloads.worker.progress('{"status":"completed","total":5,"downloaded":5}')
        self.manager.downloads.worker.code = 0
        self.manager.tick()
        self.assertTrue(any(a["path"] == model.as_posix() for a in self.manager.state["assets"]))
        self.assertEqual([], self.manager.snapshot()["downloads"])

    def test_model_used_by_task_can_be_removed_with_confirmation(self):
        path = self.root / "base-model"
        asset = next(a for a in self.manager.state["assets"] if a["kind"] == "model" and Path(a["path"]) == path)
        self.action(f"assets/{asset['id']}/remove", confirmed=True)
        self.assertFalse(path.exists())
        self.assertIn("secret", self.manager.profiles)

    def test_stop_preserves_checkpoint_and_archive_restores_record(self):
        run = self.start()
        checkpoint = self.checkpoint(run)
        self.action(f"runs/{run['id']}/stop", confirmed=True)
        self.assertEqual("interrupted", run["status"])
        self.assertTrue(checkpoint.exists())
        self.action(f"runs/{run['id']}/archive")
        self.assertTrue(run["archived"])
        self.action(f"runs/{run['id']}/restore")
        self.assertFalse(run["archived"])

    def test_asset_registration_removal_preserves_files_and_tasks(self):
        self.custom_settings()
        path = self.root / "registered.jsonl"
        path.write_text('{"messages":[],"tools":[]}\n')
        original = path.read_bytes()
        self.action("assets/create", kind="dataset", name="Training", path=str(path))
        asset = self.manager.state["assets"][-1]
        self.assertEqual("native_tools", asset["format"])
        with self.assertRaises(ValueError):
            self.action("assets/create", kind="dataset", name="Duplicate", path=str(path))
        self.action("assets/" + asset["id"] + "/remove")
        self.assertEqual(original, path.read_bytes())
        self.assertIn("secret", self.manager.profiles)
        restored = Manager(self.config, FakeChild)
        self.assertNotIn(asset["id"], [a["id"] for a in restored.state["assets"]])
        restored.close()

    def test_task_removal_retains_registered_assets(self):
        assets = list(self.manager.state["assets"])
        self.action("profiles/secret/remove", confirmed=True)
        self.assertEqual(assets, self.manager.state["assets"])
        self.assertTrue(self.profile.exists())

    def test_asset_formats_and_references(self):
        from assets import describe, register
        self.custom_settings()
        model = register(self.root, [], {"kind":"model", "name":"Base", "path":"base-model"})
        row = describe(model, [("tasks", "Task", {"base.model":model["path"]})])
        self.assertTrue(row["trainable"])
        self.assertEqual(["Task"], row["tasks"])
        gguf = self.root / "model.gguf"
        gguf.write_bytes(b"GGUFfixture")
        model = register(self.root, [], {"kind":"model", "name":"Inference", "path":str(gguf)})
        self.assertFalse(describe(model, [])["trainable"])
        invalid = self.root / "invalid.jsonl"
        invalid.write_text('{"text":"unsupported"}', encoding="utf-8")
        with self.assertRaises(ValueError):
            register(self.root, [], {"kind":"dataset", "name":"Bad", "path":str(invalid)})

    def start(self):
        self.action("profiles/secret/start", confirmed=True)
        return self.manager.state["runs"][-1]

    def custom_settings(self):
        model = self.root / "base-model"
        model.mkdir(exist_ok=True)
        (model / "config.json").write_text('{}')
        for name in ("train.jsonl", "validation.jsonl"):
            (self.root / name).write_text('{"messages":[],"tools":[]}\n', encoding="utf-8")
        return dict(DEFAULTS, **{"base.model": str(model), "training.data": str(self.root / "train.jsonl"),
                               "validation.data": str(self.root / "validation.jsonl")})

    def test_model_removal_deletes_managed_external_and_hf_cache_contents(self):
        managed = self.root / "veto-training/models/downloaded"
        external = self.root / "external-model"
        for path in (managed, external):
            path.mkdir(parents=True)
            (path / "config.json").write_text('{}')
            (path / "model.safetensors").write_bytes(b'fixture')
        self.action("assets/create", kind="model", name="Managed", path=str(managed))
        asset = self.manager.state["assets"][-1]
        self.manager.downloads.rows.append({"id": "download", "repo": "org/model", "requestedRevision": "main",
            "status": "completed", "registered": True, "path": str(managed), "downloaded": 1, "total": 1})
        self.manager.downloads.save()
        self.action(f"assets/{asset['id']}/remove", confirmed=True)
        self.assertFalse(managed.exists())
        self.assertEqual([], self.manager.downloads.rows)
        self.action("assets/create", kind="model", name="External", path=str(external))
        asset = self.manager.state["assets"][-1]
        self.action(f"assets/{asset['id']}/remove", confirmed=True)
        self.assertFalse(external.exists())
        snapshot = self.root / "cache/models--org--model/snapshots/abc"
        snapshot.mkdir(parents=True)
        (snapshot / "config.json").write_text('{}')
        (snapshot / "model.safetensors").write_bytes(b'fixture')
        self.action("assets/create", kind="model", name="Cached", path=str(snapshot))
        asset = self.manager.state["assets"][-1]
        self.action(f"assets/{asset['id']}/remove", confirmed=True)
        self.assertFalse((self.root / "cache/models--org--model").exists())

    def test_record_counts_apply_to_every_task_and_invalidate_on_change(self):
        from assets import record_count
        settings = self.custom_settings()
        self.action("profiles/create", name="Counted", settings=settings)
        row = next(p for p in self.manager.snapshot()["profiles"] if p["name"] == "Counted")
        self.assertEqual({"training.data":1,"validation.data":1}, row["counts"])
        path = self.root / "train.jsonl"
        path.write_bytes(b'{}\n\n{}')
        self.assertEqual(2, record_count(path))
        path.write_bytes(b'{}\n')
        self.assertEqual(1, record_count(path))
        self.assertIsNone(record_count(self.root / "missing.jsonl"))

    def test_core_template_preserves_purpose_and_checks_before_launch(self):
        (self.root / "base-model/tokenizer.json").unlink()
        settings = self.custom_settings()
        settings["prompt.format"] = "instruction"
        settings["sequence.length"] = "1024"
        for filename, text in (("train.jsonl", "train"), ("validation.jsonl", "validation")):
            (self.root / filename).write_text(json.dumps({"instruction":text,"output":json.dumps({"relevance":"HIGH","danger":"SAFE","reason":"test"})}) + "\n", encoding="utf-8")
        core = self.root / "core.properties"
        core.write_text("model.name=veto-slm\nprompt.format=instruction\nsequence.length=1024\n", encoding="utf-8")
        self.config["profiles"].append({"id":"screening","name":"Core","properties":"core.properties","enabled":False})
        self.action("profiles/create", name="Core run", template="screening", settings=settings)
        profile = next(p for p in self.manager.profiles.values() if p["name"] == "Core run")
        recipe = Path(profile["properties"]).read_text(encoding="utf-8")
        self.assertIn("model.name=veto-slm", recipe)
        self.assertEqual("screening", profile["purpose"])
        self.assertIsNone(self.manager.worker)
        readiness = self.manager.check_profile(profile)
        self.assertEqual({"training.data":1,"validation.data":1}, readiness["counts"])
        self.assertFalse(readiness["ready"])
        validation = self.root / "validation.jsonl"
        valid = validation.read_text(encoding="utf-8")
        validation.write_text((self.root / "train.jsonl").read_text(encoding="utf-8"), encoding="utf-8")
        self.assertTrue(any("重复" in issue for issue in self.manager.check_profile(profile)["issues"]))
        validation.write_text(json.dumps({"instruction":"different","output":"{}"}), encoding="utf-8")
        self.assertTrue(any("无效" in issue for issue in self.manager.check_profile(profile)["issues"]))
        validation.write_text(valid, encoding="utf-8")
        with self.assertRaises(ValueError):
            self.action("profiles/" + profile["id"] + "/start", confirmed=True)
        self.assertEqual([], self.manager.state["runs"])
        with patch("server.check", return_value={"ready":True,"issues":[],"counts":{}}):
            self.action("profiles/" + profile["id"] + "/start", confirmed=True)
        self.assertIn("model.name=veto-slm", Path(self.manager.state["runs"][-1]["properties"]).read_text())

    def test_edit_keeps_frozen_run_recipe(self):
        settings = self.custom_settings()
        self.action("profiles/create", name="Original", settings=settings)
        profile = next(p for p in self.manager.profiles.values() if p["name"] == "Original")
        self.action("profiles/" + profile["id"] + "/start", confirmed=True)
        run = self.manager.state["runs"][-1]
        original = Path(run["properties"]).read_bytes()
        with self.assertRaises(ValueError):
            self.action("profiles/" + profile["id"] + "/update", name="Edited", settings=settings)
        self.manager.worker.code = 1
        self.manager.tick()
        settings["epochs"] = "5"
        self.action("profiles/" + profile["id"] + "/update", name="Edited", settings=settings)
        self.assertEqual(original, Path(run["properties"]).read_bytes())
        self.assertEqual("5", self.manager.profile_settings(self.manager.profiles[profile["id"]])["epochs"])

    def test_completed_models_registered_and_visible_on_run(self):
        run = self.start()
        output = Path(run["output"])
        (output / "gguf").mkdir(parents=True)
        artifact = output / "gguf/model.gguf"
        artifact.write_bytes(b"GGUFfixture")
        self.manager.worker.code = 0
        self.manager.tick()
        self.assertEqual("completed", run["status"])
        self.assertIn(artifact.as_posix(), run["artifacts"])
        self.assertTrue(any(a["path"] == artifact.as_posix() for a in self.manager.state["assets"]))

    def test_export_retry_uses_completed_adapter_without_training_resume(self):
        run = self.start()
        output = Path(run["output"])
        adapter = output / "lora-adapter"
        adapter.mkdir(parents=True)
        (adapter / "adapter_config.json").write_text('{}')
        (adapter / "adapter_model.safetensors").write_bytes(b'adapter')
        point = self.checkpoint(run)
        (point / "complete.json").write_text('{"optimization_finished": true}')
        self.manager.worker.code = 1
        self.manager.tick()
        self.assertEqual("failed", run["status"])
        self.assertEqual([], checkpoints(output))
        self.assertTrue(self.manager.snapshot()["runs"][0]["exportReady"])
        self.action(f"runs/{run['id']}/export", confirmed=True)
        command_file = Path(self.manager.worker.args[0][-1])
        command = command_file.read_text()
        self.assertIn("action=export", command)
        self.assertNotIn("resume.checkpoint", command)
        self.assertEqual("exporting", run["status"])
        (output / "gguf").mkdir()
        (output / "gguf/model.gguf").write_bytes(b"GGUFfixture")
        self.manager.worker.code = 0
        self.manager.tick()
        self.assertEqual("completed", run["status"])

    def test_custom_recipe_persists_without_starting_training(self):
        settings = self.custom_settings()
        self.action("profiles/create", name="我的训练", settings=settings)
        self.assertIsNone(self.manager.worker)
        self.assertEqual([], self.manager.state["runs"])
        restored = Manager(self.config, FakeChild)
        profile = next(p for p in restored.profiles.values() if p["name"] == "我的训练")
        self.assertEqual("16", restored.profile_settings(profile)["lora.rank"])
        identifier = profile["id"]
        self.action(f"profiles/{identifier}/start", confirmed=True)
        run = self.manager.state["runs"][-1]
        self.assertIn("veto-training/models", Path(run["output"]).as_posix())
        self.assertNotEqual(profile["properties"], run["properties"])
        self.assertIn("learning.rate=0.0001", Path(run["properties"]).read_text())

    def test_remove_task_persists_and_retains_frozen_run_and_model(self):
        run = self.start()
        point = self.checkpoint(run)
        self.manager.worker.code = 1
        self.manager.tick()
        self.action("profiles/secret/remove")
        restored = Manager(self.config, FakeChild)
        self.assertNotIn("secret", restored.profiles)
        self.assertEqual(run["id"], restored.state["runs"][0]["id"])
        self.assertTrue(point.exists())
        self.assertTrue(Path(run["properties"]).exists())
        self.action(f"runs/{run['id']}/resume", checkpoint=point.name, confirmed=True)
        self.assertIsNotNone(self.manager.worker)

    def test_remove_running_task_is_rejected(self):
        self.start()
        with self.assertRaisesRegex(ValueError, "暂停"):
            self.action("profiles/secret/remove")
        self.assertIn("secret", self.manager.profiles)

    def test_removed_custom_task_no_longer_uses_capacity_or_name(self):
        settings = self.custom_settings()
        self.action("profiles/create", name="temporary", settings=settings)
        identifier = next(k for k in self.manager.profiles if k.startswith("custom-"))
        self.action(f"profiles/{identifier}/remove")
        self.action("profiles/create", name="temporary", settings=settings)
        self.assertEqual(1, len(self.manager.state["customProfiles"]))

    def test_custom_recipe_rejects_command_injection_and_invalid_parameters(self):
        settings = self.custom_settings()
        for changes in ({"python":"malicious"}, {"epochs":"3\naction=train"},
                        {"learning.rate":"NaN"}, {"lora.dropout":"1"},
                        {"batch.size":"0"}, {"sequence.length":"9999999"}):
            with self.subTest(changes=changes), self.assertRaises(InvalidProfile):
                validate(self.root,"任务",dict(settings,**changes))
        self.assertIsNone(self.manager.worker)

    def test_custom_recipe_checks_data_and_local_model(self):
        settings = self.custom_settings()
        for key, value in [("base.model", "absent"), ("training.data", "missing.jsonl"),
                           ("validation.data", settings["training.data"]), ("prompt.format", "unsupported")]:
            with self.subTest(key=key), self.assertRaises(InvalidProfile):
                validate(self.root,"任务",dict(settings,**{key:value}))
        Path(settings["validation.data"]).write_text('{"instruction":"x","output":"y"}\n')
        with self.assertRaises(InvalidProfile) as error:
            validate(self.root,"任务",settings)
        self.assertEqual("validation.data",error.exception.field)

    def test_duplicate_recipe_name_is_not_saved_twice(self):
        settings = self.custom_settings()
        self.action("profiles/create",name="任务",settings=settings)
        with self.assertRaises(InvalidProfile):
            self.action("profiles/create",name="任务",settings=settings)
        self.assertEqual(1,len(self.manager.state["customProfiles"]))

    def checkpoint(self, run):
        point = Path(run["output"]) / "checkpoint-100"
        point.mkdir(parents=True)
        (point / "complete.json").write_text('{"optimization_finished": false}')
        return point

    def test_confirmation_and_stale_revision_do_not_launch(self):
        with self.assertRaises(ValueError):
            self.action("profiles/secret/start")
        with self.assertRaises(ValueError):
            self.manager.action(["profiles", "secret", "start"], {"revision": -1, "confirmed": True})
        self.assertIsNone(self.manager.worker)

    def test_duplicate_start_rejected(self):
        run = self.start()
        self.assertTrue(Path(run["properties"]).is_file())
        with self.assertRaises(ValueError):
            self.start()
        self.assertEqual(1, len(self.manager.state["runs"]))

    def test_resume_uses_same_output_and_selected_checkpoint(self):
        run = self.start()
        point = self.checkpoint(run)
        self.manager.worker.code = 125
        self.manager.tick()
        self.action(f"runs/{run['id']}/resume", checkpoint=point.name, confirmed=True)
        command_file = Path(self.manager.worker.args[0][-1])
        self.assertIn("action=resume", command_file.read_text())
        self.assertIn(point.as_posix(), command_file.read_text())
        self.assertEqual(1, len(self.manager.state["runs"]))

    def test_resume_rejects_foreign_or_incomplete_checkpoint(self):
        run = self.start()
        self.manager.worker.code = 1
        self.manager.tick()
        for name in ("../checkpoint-100", "checkpoint-99"):
            with self.assertRaises(ValueError):
                self.action(f"runs/{run['id']}/resume", checkpoint=name, confirmed=True)

    def test_pause_while_starting_is_queued_until_worker_lock_exists(self):
        run = self.start()
        self.action(f"runs/{run['id']}/pause")
        self.assertEqual("pause_pending", run["status"])
        output = Path(run["output"])
        output.mkdir(parents=True)
        (output / "training.lock").touch()
        with patch("server.subprocess.run") as call:
            call.return_value.returncode = 0
            self.manager.tick()
        self.assertEqual("pausing", run["status"])

    def test_pause_request_is_not_saved_state(self):
        run = self.start()
        point = self.checkpoint(run)
        (point.parent / "training.lock").touch()
        with patch("server.subprocess.run") as call:
            call.return_value.returncode = 0
            self.action(f"runs/{run['id']}/pause")
            self.assertIn("TrainingCli", " ".join(call.call_args.args[0]))
        self.assertEqual("pausing", run["status"])
        (point.parent / "paused.checkpoint").write_text(point.name)
        self.manager.worker.code = 0
        self.manager.tick()
        self.assertEqual("paused", run["status"])

    def test_nonzero_exit_cannot_claim_pause_or_completion(self):
        run = self.start()
        point = self.checkpoint(run)
        (point.parent / "paused.checkpoint").write_text(point.name)
        self.manager.worker.code = 1
        self.manager.tick()
        self.assertEqual("failed", run["status"])

    def test_restart_marks_active_run_interrupted_and_never_restarts_training(self):
        self.start()
        restarted = Manager(self.config, FakeChild)
        self.assertEqual("interrupted", restarted.state["runs"][0]["status"])
        self.assertIsNone(restarted.worker)

    def test_finished_optimization_is_not_resumable(self):
        run = self.start()
        point = self.checkpoint(run)
        (point / "complete.json").write_text('{"optimization_finished": true}')
        self.assertEqual([], checkpoints(point.parent))

    def test_progress_never_exposes_input_or_raw_messages(self):
        run = self.start()
        self.manager.progress(run, json.dumps({"step": 5, "loss": .25, "input": "SECRET", "message": "SECRET"}))
        self.assertNotIn("SECRET", json.dumps(self.manager.snapshot()))
        self.assertEqual({"step": 5, "loss": .25}, run["progress"])

    def test_disk_stop_preserves_recovery_files(self):
        run = self.start()
        point = self.checkpoint(run)
        self.config["modelLimitBytes"] = 1
        worker = self.manager.worker
        self.manager.tick()
        self.assertTrue(worker.closed)
        self.assertEqual("interrupted", run["status"])
        self.assertTrue(point.exists())

    def test_stopping_service_waits_for_pause_and_closes_backend(self):
        run = self.start()
        point = self.checkpoint(run)
        (point.parent / "training.lock").touch()
        self.manager.backend = backend = FakeChild()
        self.manager.frontend = frontend = FakeChild()
        with patch("server.subprocess.run") as call:
            call.return_value.returncode = 0
            self.action("service/stop")
        self.assertFalse(self.manager.done.is_set())
        (point.parent / "paused.checkpoint").write_text(point.name)
        self.manager.worker.code = 0
        self.manager.tick()
        self.assertTrue(self.manager.done.is_set())
        self.assertTrue(backend.closed)
        self.assertTrue(frontend.closed)

    def test_application_environment_and_restart_limit(self):
        self.config["backend"] = {"port": 8443, "command": ["fixture"],
                                  "environment": {"JAVA_TOOL_OPTIONS": "fixture-plugin-directory"}}
        with patch("server.socket.create_connection", side_effect=OSError):
            for _ in range(3):
                self.manager.tick()
                self.assertIsNotNone(self.manager.backend)
                self.assertEqual("fixture-plugin-directory", self.manager.backend.args[2]["JAVA_TOOL_OPTIONS"])
                self.assertEqual(10_000_000_000, self.manager.backend.options["memory_limit"])
                self.manager.backend.code = 1
            self.manager.tick()
        self.assertIsNone(self.manager.backend)
        self.assertEqual("failed", self.manager.backend_status)

    def test_existing_foreign_listener_is_preserved(self):
        self.config["backend"] = {"port": 8443, "command": ["fixture"]}
        with patch("server.socket.create_connection"):
            self.manager.tick()
        self.assertIsNone(self.manager.backend)
        self.assertEqual("external", self.manager.backend_status)

    def test_service_stop_during_export_waits_without_requesting_pause(self):
        run = self.start()
        self.manager.progress(run, '{"type":"phase_complete","phase":"training"}')
        self.action("service/stop")
        self.assertTrue(self.manager.stopping)
        self.assertFalse(self.manager.done.is_set())
        self.assertEqual("exporting", run["status"])

    def test_disabled_profile_cannot_launch_through_api(self):
        self.manager.profiles["secret"]["enabled"] = False
        with self.assertRaises(ValueError):
            self.start()
        self.assertIsNone(self.manager.worker)

    def test_http_security_and_real_state(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.manager))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{server.server_port}"
        try:
            with urlopen(url + "/api/state") as response:
                state = json.load(response)
            assets = self.root / "assets-fixture"
            (assets / "assets").mkdir(parents=True)
            (assets / "index.html").write_text('<meta name="csp-nonce" content="__VETO_NONCE__">')
            (assets / "assets/app-test.js").write_text('console.log("fixture")')
            with patch("server.ASSETS", assets):
                with urlopen(url + "/") as response:
                    html = response.read().decode()
                    policy = response.headers["Content-Security-Policy"]
                    self.assertNotIn("__VETO_NONCE__", html)
                    self.assertIn("'nonce-", policy)
                    self.assertNotIn("unsafe-inline", policy)
                    self.assertIn(html.split('content="')[1].split('"')[0], policy)
                with urlopen(url + "/assets/app-test.js") as response:
                    self.assertEqual(200, response.status)
                for path in ("/assets/../server.py", "/assets/%2e%2e/server.py", "/app.js"):
                    with self.assertRaises(HTTPError) as missing:
                        urlopen(url + path)
                    self.assertEqual(404, missing.exception.code)
            body = json.dumps({"revision": state["revision"], "confirmed": True}).encode()
            for headers in ({}, {"Origin": "https://evil.example", "X-Veto-CSRF": state["csrf"]}):
                with self.assertRaises(HTTPError) as failure:
                    urlopen(Request(url + "/api/profiles/secret/start", body, headers))
                self.assertEqual(403, failure.exception.code)
            with urlopen(Request(url + "/api/profiles/secret/start", body,
                         {"Origin": url, "X-Veto-CSRF": state["csrf"]})) as response:
                self.assertEqual(202, response.status)
            with self.assertRaises(HTTPError) as failure:
                urlopen(Request(url + "/api/state", headers={"Host": "evil.example"}))
            self.assertEqual(403, failure.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_scoped_accounting_preserves_other_roots(self):
        scope, other = self.root / "managed", self.root / "other"
        scope.mkdir()
        other.mkdir()
        (other / "keep").write_bytes(b"123")
        budget = Budget({"roots": [str(scope), str(other)], "chargedBytes": 100,
                         "limitBytes": 10_000_000}, self.root)
        (scope / "sample").write_bytes(b"12345")
        budget.check_scope(scope)
        self.assertEqual(105, budget.state["used"])
        self.assertEqual(3, budget.state["files"][str(other / "keep")])
        budget.check()
        self.assertEqual(105, budget.state["used"])

    def test_accounting_survives_restart_and_counts_recreated_files(self):
        scope = self.root / "scope"
        scope.mkdir()
        config = {"roots": [str(scope)], "chargedBytes": 100, "limitBytes": 10_000_000}
        budget = Budget(config, self.root)
        path = scope / "log"
        path.write_bytes(b"x" * 200)
        budget.check()
        self.assertEqual(300, budget.state["used"])
        path.unlink()
        budget.check()
        path.write_bytes(b"x" * 200)
        restarted = Budget(config, self.root)
        self.assertEqual(500, restarted.state["used"])

    @unittest.skipUnless(os.name == "nt", "Windows process ownership")
    def test_real_java_cli_start_pause_resume_and_export(self):
        repo = Path(__file__).resolve().parents[2]
        jar = repo / "veto-training/build/libs/veto-training-1.0.100.jar"
        java_home = os.environ.get("JAVA_HOME")
        self.assertTrue(java_home and jar.exists(), "Build the training JAR and configure JAVA_HOME")
        scripts = self.root / "veto-training/python"
        scripts.mkdir(parents=True)
        (self.root / "llama").mkdir()
        (self.root / "llama/convert_hf_to_gguf.py").touch()
        (self.root / "train.jsonl").write_text('{"messages":[],"tools":[]}\n')
        (self.root / "eval.jsonl").write_text('{"messages":[],"tools":[]}\n')
        (self.root / "fixture").mkdir()
        for filename in ("config.json", "tokenizer.json", "model.safetensors"):
            (self.root / "fixture" / filename).write_text('{}')
        (self.root / "llama/llama-quantize.exe").touch()
        (scripts / "train.py").write_text('''
import argparse,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output-dir');p.add_argument('--resume-from-checkpoint')
a,_=p.parse_known_args();out=Path(a.output_dir)
if a.resume_from_checkpoint:
 (out/'merged').mkdir()
else:
 deadline=time.monotonic()+30
 while not (out/'pause.request').exists():
  if time.monotonic()>deadline: raise RuntimeError('Fixture pause timed out')
  time.sleep(.05)
 checkpoint=out/'checkpoint-1';checkpoint.mkdir();(checkpoint/'complete.json').write_text('{"optimization_finished":false}')
 (out/'paused.checkpoint').write_text(checkpoint.name)
''', encoding="utf-8")
        (scripts / "convert_to_gguf.py").write_text('''
import argparse
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output-dir');p.add_argument('--model-name')
a,_=p.parse_known_args();out=Path(a.output_dir);out.mkdir()
(out/(a.model_name+'-q4_k_m.gguf')).write_bytes(b'fixture')
''', encoding="utf-8")
        import sys
        self.profile.write_text(f"""python={Path(sys.executable).as_posix()}
llama.cpp=llama
base.model=fixture
training.data=train.jsonl
validation.data=eval.jsonl
output.directory=models/initial
model.name=fixture
prompt.format=native_tools
timeout.seconds=60
""", encoding="utf-8")
        self.config.update(java=str(Path(java_home) / "bin/java.exe"), trainingJar=str(jar))
        self.manager.factory = lambda *args, **kwargs: Child(*args, backend=True, **kwargs)
        run = self.start()
        deadline = time.monotonic() + 30
        while not (Path(run["output"]) / "training.lock").exists():
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.1)
        self.action(f"runs/{run['id']}/pause")
        while self.manager.worker:
            self.assertLess(time.monotonic(), deadline)
            self.manager.tick()
            time.sleep(.1)
        self.assertEqual("paused", run["status"], (self.manager.state_dir / (run["id"] + ".log")).read_text(encoding="utf-8", errors="replace"))
        self.action(f"runs/{run['id']}/resume", checkpoint="checkpoint-1", confirmed=True)
        while self.manager.worker:
            self.assertLess(time.monotonic(), deadline)
            self.manager.tick()
            time.sleep(.1)
        self.assertEqual("completed", run["status"])
        self.assertEqual([], checkpoints(Path(run["output"])))


if __name__ == "__main__":
    unittest.main()
