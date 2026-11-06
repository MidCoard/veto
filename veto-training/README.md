# Shared model training

Offline Java API used by core screening and secret protection. Java starts and
monitors the shared Python QLoRA trainer, then exports GGUF with llama.cpp.
This module is excluded from the backend, plugin packages and user release bundle.

## Local management page

The Windows `veto-service.ps1` entry point manages the core process and a separate
loopback training console. They share a repository, not a runtime: the console
calls this module's Java CLI; restarting core does not restart training.
`Start` installs the current-user sign-in shortcut if necessary. `Status` reads actual
state; `Stop` waits for training to save before retiring the managed backend.
`Uninstall` removes automatic startup after the service has stopped.

```powershell
.\veto-service.ps1 -Action Start
.\veto-service.ps1 -Action Status
.\veto-service.ps1 -Action Stop
```

Local configuration lives in ignored `work/tmp/training-service/config.json`.
It supplies executable paths, preset training profiles, pinned backend JAR,
output limits and accounting roots. The page supports explicit starts, pause
requests and checkpoint selection. Service restarts never automatically retrain.
Create custom tasks in the page with a local Hugging Face model directory,
separate JSONL datasets, data format and training parameters. Saving a task does
not run it. Each run freezes its recipe; checkpoint resume uses that same recipe.
The save-time data check covers the first record, not the complete dataset.
Existing foreign backend listeners are reported as external rather than killed.
The startup shortcut runs at user sign-in, not before login; opening the page does
not require starting another frontend server. This console is developer-only and
is excluded from release artifacts. See [service/DESIGN.md](service/DESIGN.md) for
the deliberately separate UI scope.

## Java API

```java
ModelTrainer trainer = new PythonModelTrainer(
        List.of(pythonExecutable), repository.resolve("veto-training/python"),
        repository, llamaCppDirectory, Duration.ofHours(4));
TrainingRequest request = TrainingCli.request(repository, taskProperties);
ModelTrainer.Result result = trainer.train(request, System.out::println);
if (result instanceof ModelTrainer.Completed completed) {
    Path artifact = completed.gguf();
}
```

`train` blocks until export completes or a pause is saved. From another thread or
process, call `trainer.requestPause(request.outputDirectory())`. The request is
acknowledged by a `ModelTrainer.Paused` result containing the complete checkpoint.
Continue with `trainer.resume(request, paused.checkpoint(), progress)` using the
same run and configuration. After a merge/conversion failure,
`trainer.export(request, progress)` reuses the merged model, saved adapter or an
optimization-finished checkpoint without rerunning optimization. A file lock
rejects concurrent writers. Interrupting the calling thread remains emergency
cancellation; it does not save the current step.

Checkpoints save optimizer, scheduler, RNG and Trainer/callback state at epoch
boundaries and every `checkpoint.steps` optimizer updates (default 100). Pausing
waits for an optimizer boundary; the checkpoint is marked complete only after all
state is written. Resume checks file hashes, model/data/configuration and runtime
versions. Incomplete or incompatible checkpoints are rejected. Retention keeps
two checkpoints, including the best when applicable. A crash may lose work since
the last complete checkpoint. A pause arriving after optimization completes may
receive `Completed` instead. Export recovery is separate from mid-training resume.

Fresh training rejects existing output directories. A completed artifact is not
a quality approval or a runtime deployment. Model files and checkpoints remain ignored.

## Task configuration

- Core: [`screening.properties`](../veto-core/training/screening.properties).
- Secret: [`secret.properties`](../veto-secret-protection/training/secret.properties).

Paths resolve from the repository root. Set `python` to an interpreter with
[`python/requirements.txt`](python/requirements.txt) installed, and `llama.cpp` to
a directory containing `convert_hf_to_gguf.py`. `llama-quantize` must be in that
directory, its build directory, or `PATH`. The checked-in interpreter path targets
the existing Windows environment; use a local properties file on other hosts.
Choose a new `output.directory` for each run.

Prepare data with the task owner's tools before training. Core's
`prepare_screening_data.py` produces screening records; secret's
`prepare_agent_data.py` cleans and annotates the source corpus, and
`:veto-secret-protection:secretSft` exports native tool conversations with the
production-compiled prompt and schema. `evaluate_agent.py` scores recorded native
tool calls against that export, including visibility and completion. Each task
owns validation and evaluation.
Training and validation files must differ; keep final test data out of training
and checkpoint selection.

Native-tool training keeps the full conversation but projects vocabulary logits
only where the next token is supervised. This preserves causal loss and gradients
while reducing long-context memory use. It requires a model supporting
`logits_to_keep` and aligned `shift_labels`; Qwen2 is covered by the tests.

```text
./gradlew :veto-core:trainModel
./gradlew :veto-secret-protection:trainModel
```

Use `-PtrainingConfig=<properties-file>` to select another configuration.
Set `action=pause` to request a pause of that configuration's `output.directory`.
Set `action=resume` and `resume.checkpoint=<checkpoint-directory>` to continue it;
omit `action` or use `action=train` for a fresh run. Use `action=export` after a
failed merge/conversion to reuse completed outputs without training. Keep all
other task settings unchanged on resume/export. These actions are shared by both
Gradle training tasks. Train/resume perform real optimization; export does not.
The two tasks share `TrainingCli`, `PythonModelTrainer`, `train.py`, `export.py`
and `convert_to_gguf.py`; they retain separate
datasets, prompt formats and model names. Weights and checkpoints stay in ignored
model directories and must not enter Git or Git LFS.

## Verification

`./gradlew :veto-training:test` exercises both task configurations with real fixture
subprocesses, including failure, timeout, cancellation and artifact isolation.
It does not train a model. Python unit tests run with
`python -B -m unittest discover -s veto-training/python` from the repository root.

Trainer UI uses React 18 + Material UI. In `veto-training/service/web`, run `npm ci`, `npm test`, and `npm run build` before starting the service. The service serves `web/dist` at port 8778; Node is needed for builds, not at runtime. Task removal retains run history and model files.

The Model page uses one Add Model dialog for Hugging Face downloads and existing local content, with pause/resume, automatic registration and task/run references. Downloads pin a repository revision; optional access tokens are held only by the active worker. Models used by tasks remain removable; the confirmation names affected tasks and requires a replacement before future save/start. Every removal permanently deletes the actual model after a full-path warning, including the complete Hugging Face cache entry. New tasks select registered trainable models by name and must use a managed dataset. Dataset management owns JSONL import and the prompt format; task forms contain no JSONL/path source controls.

Create Task offers Core intent and Secret templates from host configuration, resolves cached base models locally, and preserves each task's prompt format and model name. Edit changes future runs only. Save validates settings; start automatically verifies model/runtime and input files. Core additionally checks all labels and train/validation overlap within 20 MB per split. These checks do not load the model, measure quality, or guarantee GPU capacity. Completed GGUF/merged outputs appear in the run and Model page; activation in Core is separate from training. Runs expose logs, pause/save, checkpoint resume, immediate stop, and reversible archiving.

The Dataset page manages sharded JSONL files under ignored `work/training-datasets`, split at 1,000 records or 32 MB. Create, rename or archive a dataset, upload or import JSONL, preview three records per file, assign train/validation/test splits, and enable/disable or permanently delete files. Selecting a dataset for a task publishes a fixed snapshot of enabled files automatically; no manual snapshot or recycle-bin action remains. Import preserves source files; changes never alter existing run inputs. Search matches file names.
