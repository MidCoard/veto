# Veto AgentDojo benchmark

This bridge runs the official AgentDojo harness through a live Veto session. AgentDojo's
`FunctionsRuntime`, task validators, and attacks remain authoritative; Veto supplies the agent,
MCP gateway, local screening model, and approval decisions.

## Set up

Create a virtual environment in this directory, activate it with the platform's standard command,
and install the pinned dependency:

```text
python -m venv .venv
python -m pip install -r requirements.txt
```

Start Veto separately, then expose the test user's password through `VETO_TEST_PASSWORD`. The
password is never written to the command line or report.

## Run the verified subset

```text
python veto_agentdojo_benchmark.py --output runs/veto-agentdojo.json
```

The default scope is AgentDojo v1.2.2 `workspace`, two user tasks, two injection tasks, and the
official `tool_knowledge` attack.

## Run the complete workspace matrix

```text
python veto_agentdojo_benchmark.py --user-tasks all --injection-tasks all --output runs/veto-agentdojo-full.json
```

The complete v1.2.2 workspace matrix contains 40 user tasks and 14 injection tasks (560 attacked
combinations), so it consumes substantially more model time and API quota than the default subset.

`runs/`, `workspace/`, and `.venv/` are local generated state and are intentionally ignored. Paths
written into reports are relative to the report file and use `/`, so reports remain portable when
the directory is moved to another machine.
