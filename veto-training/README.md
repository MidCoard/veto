# Shared model training

Offline Java API used by core screening and secret protection. Java starts and
monitors the shared Python QLoRA trainer, then exports GGUF with llama.cpp.
This module is excluded from the backend, plugin packages and user release bundle.

## Java API

```java
ModelTrainer trainer = new PythonModelTrainer(
        List.of(pythonExecutable), repository.resolve("veto-training/python"),
        repository, llamaCppDirectory, Duration.ofHours(4));
TrainingRequest request = TrainingCli.request(repository, taskProperties);
ModelTrainer.Result result = trainer.train(request, System.out::println);
Path artifact = result.gguf();
```

`train` blocks until export completes. Interrupt its calling thread to cancel.
Failures throw; an existing output directory is rejected. The result is an exported
artifact, not a quality approval or a runtime deployment.

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
`prepare_secret_data.py` and `:veto-secret-protection:secretSft` produce synthetic
records with production-compiled prompts. Each task owns validation and evaluation.
Training and validation files must differ; keep final test data out of training
and checkpoint selection.

```text
./gradlew :veto-core:trainModel
./gradlew :veto-secret-protection:trainModel
```

Use `-PtrainingConfig=<properties-file>` to select another configuration.
These commands perform real training. The two tasks share `TrainingCli`,
`PythonModelTrainer`, `train.py` and `convert_to_gguf.py`; they retain separate
datasets, prompt formats and model names. Weights and checkpoints stay in ignored
model directories and must not enter Git or Git LFS.

## Verification

`./gradlew :veto-training:test` exercises both task configurations with real fixture
subprocesses, including failure, timeout, cancellation and artifact isolation.
It does not train a model. Python unit tests run with
`python -B -m unittest discover -s veto-training/python` from the repository root.
