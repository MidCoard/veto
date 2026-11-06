# Evaluate the live Veto agent with Inspect

Inspect schedules samples and records scores. Veto still runs its own model routing,
agent loop, tools, screening, approvals and plugins. This runner does not substitute
an Inspect agent for Veto. It uses authenticated protocol 5 on `/ws/veto/bus`, with
REST only for authentication and session resources.

## Run

Use Python 3.11 or newer. From the repository root, create a task environment in an
ignored temporary directory and install the pinned direct dependencies:

```powershell
python -m venv work/tmp/veto-evaluation/venv
work/tmp/veto-evaluation/venv/Scripts/python.exe -m pip install -r veto-core/benchmarks/requirements.txt
$env:VETO_TEST_USERNAME = "<evaluation-user>"
$env:VETO_TEST_PASSWORD = "<evaluation-password>"
$env:PYTHONPATH = "$PWD/veto-core/benchmarks"
work/tmp/veto-evaluation/venv/Scripts/python.exe -m veto_eval --suite smoke --pattern <pattern>
```

Start a backend with the root `start-backend.ps1` / `start-backend.sh` launcher first.
Use an evaluation account and inspect the configured pattern before running: these
are real model calls and can incur provider costs. The `smoke` suite requires the
`top.focess.builtin` plugin, and model calls require `top.focess.llm-providers` by default.
The defaults select the provider, builtin and secret-protection plugins. Use `--plugins`
for an explicit comma-separated selection. Each sample gets a new session and workspace; the runner
deletes its session and logs out afterward, retaining the workspace for inspection.
Existing user sessions and historical reports are not selected or modified.

For AgentDojo, use a **dedicated backend process** and an evaluation account with MCP
discovery permission. The current MCP registry is process-global. Stop that backend
after the run so its temporary MCP endpoint is not retained. One invocation registers
one endpoint; restarting the backend is required before another AgentDojo invocation.

```powershell
work/tmp/veto-evaluation/venv/Scripts/python.exe -m veto_eval --suite all --pattern <pattern> `
  --user-tasks user_task_0 --injection-tasks injection_task_0 --timeout 180
```

`--suite agentdojo` skips the local smoke tasks. Task lists are comma-separated
official IDs. `--epochs 2` repeats every selected sample with fresh state; the default
is one epoch. Execution is sequential because the MCP endpoint owns one active
AgentDojo world. The default timeout is 180 seconds per request. Timeout or evaluator
cancellation sends `Cancel` and waits up to 30 more seconds for its matching final
`CancelResult(CANCELLED)`, or the original `Done/Error` if normal completion won.
Answer text and rejected cancellation do not prove settlement. Unconfirmed timeout
preserves the failure and workspace; it never reports cancellation success.

## What the scores mean

| Task | What is independently checked |
| --- | --- |
| `veto_smoke / file-total` | Read a CSV, write the correct total to a real file, preserve the input. |
| `veto_smoke / ask-user` | Publish an actual question, consume the scripted answer, write that choice to a file. |
| `agentdojo_clean` | Official AgentDojo utility validator on a fresh clean environment. |
| `agentdojo_attacked` | Official utility and injection-goal validators after the `tool_knowledge` attack. |

AgentDojo uses package `0.1.35`, benchmark version `v1.2.2`, suite `workspace`. Its
official task runner supplies the environment, initialization and validators. Actual
MCP calls supply the tool trace used by those validators. The integration subset does
not run the complete upstream matrix or its separate injection-goal-as-user controls;
do not present it as a published leaderboard result.

`task_success = 1` means the task validator passed. `attack_success = 1` means the
attack achieved its goal. Clean tasks have **no** attack-success score. Missing
results, timeouts and adapter failures appear as sample errors, excluded from score
denominators with coverage reported separately. A zero attack-success value on a tiny
subset is not evidence that Veto is broadly secure.
Settled failed requests retain records and usage under `partial_result` in the log;
the summary includes that usage but never assigns a score to the partial execution.

Approvals use a fixed simulated-user policy, `screened-one-call-v1`: decline calls
whose screening says LOW relevance or DANGEROUS/CRITICAL risk; otherwise choose an
offered approval for this call only. Unsupported interactions are errors. Reports
include the offered screening and selected options so this policy remains part of
the evaluated system. Ask-user answers are keyed by exact question ID, not guessed
by another model. AgentDojo sessions retain the configured provider/security plugins
but omit `top.focess.builtin`; smoke sessions use the full configured selection.

Each invocation creates a fresh directory below `work/tmp/veto-evaluations` (override
with `--output`). Start with `report.txt` for readable per-sample outcomes, errors,
durations and actual model usage. The directory also contains `summary.json`, configuration/dependency provenance,
Inspect `.eval` logs, and workspaces. Logs retain Veto session records (including
child agents and rewound work), measured model usage,
approvals and actual tool results. Treat them as private task data. Credentials are
read at runtime and are not registered solver arguments or report metadata.

Inspect runs with `model=None`: Veto owns all model calls. Therefore Inspect's own
provider token counter is not Veto's usage; use the Veto measurements in sample
metadata. The model pattern is a routing choice, not a fixed model ID. Costs remain
unknown when there is no price measurement. Record the actual model IDs from history
when comparing runs. Summary metrics are per sample/epoch, with their denominator.

Exit status 1 means incomplete coverage or an execution/scoring error. A fully executed
sample with `task_success = 0` is a measured agent failure and does not make the runner
itself fail. Read the scores as well as the exit status.

Open the resulting logs with the installed `inspect view --log-dir <run>/logs` command.
The former REST-polling bridge and standalone report runner have been removed;
historical data under `agentdojo/runs` is retained, not silently reused.

## Verify the adapter

From `veto-core/benchmarks`, with the environment active:

```powershell
python -m unittest discover -s tests -v
```

These focused tests verify protocol settlement, cancellation, cleanup, actual local
WebSocket I/O, artifact checking, official MCP tool execution, and Inspect's error and
credential handling. Test doubles are harness checks only and are not agent scores.
Backend changes still require the repository gate `spotlessCheck test :veto-core:bootJar`.
