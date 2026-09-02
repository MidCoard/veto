# Veto

Veto is a local agent runtime that places an explicit security and approval boundary between a
cloud reasoning model and the host machine. The model proposes tool calls; Veto validates their
arguments, screens their intent and risk, asks for human approval when required, executes approved
calls locally, and records the resulting turns and decisions.

This repository contains the Java backend, the shared wire protocol, and the interactive terminal.
The web client lives in the separate [veto-ui](https://github.com/MidCoard/veto-ui) repository.

## Current status

Veto is under active development. The following paths are implemented in this repository:

- autonomous and guided agent-loop execution;
- native file, search, command, background-task, web, memory, skill, and group tools;
- deterministic tool screening, local-SLM advisory screening, permission grants, and HITL vetoes;
- session history, long-term memory backends, model-tier profiles, and per-user vault storage;
- REST, WebSocket, gRPC, and local ZeroMQ transport surfaces;
- an interactive terminal client;
- a local SLM preparation, training, evaluation, GGUF conversion, and deployment pipeline;
- an official AgentDojo bridge for security and utility evaluation.

Some larger architectural goals are only partial or experimental. In particular, the local
subprocess substrate is not yet a complete hostile-code sandbox, Linux still lacks bundled
Bubblewrap and aggregate cgroup limits, distributed swarm coordination is not implemented, and the
local SLM can still misclassify legitimate tool calls. See
[Security boundaries](#security-boundaries) before using Veto with untrusted code or on a
multi-user host.

## Request flow

```text
User / UI / terminal
        |
        v
Session and agent loop
        |
        v
Tool argument validation
        |
        v
Gateway
  - deterministic danger floor
  - local-SLM relevance and semantic-danger advice
  - permission grants and screening mode
        |
        +---- reject / request human approval
        |
        v
Native tool, external MCP tool, or agent-control tool
        |
        v
Local execution substrate and masked observation
        |
        v
Turn history, event stream, and audit record
```

The deterministic layer is authoritative. The local SLM is defense in depth: if it is unavailable
or its output cannot be parsed, screening degrades to high relevance and safe advisory danger while
the deterministic danger result remains active.

## Repository layout

```text
veto/
|-- veto-protocol/   Shared frames, transports, client-session state, and serialization contracts
|-- veto-core/       Spring Boot backend, agent runtime, security gateway, persistence, and tools
|-- veto-terminal/   JLine/Mordant terminal application; depends on veto-protocol, not veto-core
|-- gradle/          Gradle wrapper support
|-- qodana.yaml      Qodana inspection configuration
|-- ignorekit.json   Source of truth for generated .gitignore rules
|-- LICENSE          GNU AGPL v3.0-only license
`-- README.md        This document
```

Important `veto-core` packages:

| Package | Responsibility |
| --- | --- |
| `agent` | Agent lifecycle, runner, loop breaker, and service orchestration |
| `agent.loop` | Prompt compilation, guided actions, context management, and response enforcement |
| `agent.intercept` | Gateway results, HITL decisions, permission grants, drift, and ingress handling |
| `agent.screening` | Deterministic and SLM-assisted relevance/danger computation |
| `agent.mcp` | Current unified tool runtime, schemas, native/agent tool SPI, and MCP client transport |
| `agent.workspace` | Workspace roots, path resolution, and workspace policy inputs |
| `sandbox` | Process execution, background-task lifecycle, file operations, and kernel-wall adapters |
| `session` | Session creation, activation, ownership, and history loading |
| `memory` | In-memory, JPA, vector, and pgvector memory implementations |
| `group` | Leader/mate groups, DAG coordination, and blackboard state |
| `vault` | Authentication lifecycle and encrypted per-user notes/credentials |
| `veto` | llama.cpp bridge, grammar enforcement, redaction, and gateway-level model services |
| `training` | Training lifecycle and REST endpoints |
| `controller` | REST API controllers used by veto-ui and benchmark bridges |
| `terminal` | Backend side of the ZeroMQ terminal protocol |
| `bus` | WebSocket/gRPC event and routing infrastructure |
| `observability` | Audit chain and runtime events |

### Known package-structure debt

`agent.mcp` is currently overloaded. Most classes in it describe Veto's generic tool runtime and
built-in tools, while only `McpJsonRpcClient`, `McpTransport`, and remote tool discovery are
specifically MCP. A future package refactor should separate the generic tool API/engine, built-in
tools, schema compilation, agent-control tools, and the external MCP adapter. Until that migration
is performed atomically, the table above describes the code as it exists rather than implying that
every native tool is itself an MCP implementation.

## Requirements

- JDK 25
- the included Gradle 9.1 wrapper; a system Gradle installation is not required
- PostgreSQL for the default `jpa` memory and session persistence configuration
- optional: a llama.cpp `llama-server` executable and a compatible GGUF model for local screening
- optional: Python and the dependencies under `veto-core/training/python/` for model training and
  evaluation

Veto is developed on Windows and uses platform-specific launchers where appropriate. The Gradle
build also supports Unix-like hosts, but kernel sandbox behavior differs by operating system.

## Build and verify

Windows:

```powershell
.\gradlew.bat check
```

The Windows sandbox does not install or copy Java, Node.js, Python, or other toolchains. Commands
remain direct `executable + argv[]` launches and inherit the host terminal environment. Veto resolves
executables with the host `PATH`/`PATHEXT`, then projects only the selected executable's containing
execution root into the per-workspace AppContainer. `PATH` is lookup input, not a blanket ACL grant.
Cache-valued roots, the workspace, and the sandbox temporary directory are writable. This
reachability is not a trust classification: every executable is still screened by the Gateway, and
ProtectedSet entries are masked from the AppContainer. Workspace and ProtectedSet ACLs are required
and fail closed. Compatibility grants are best-effort because a non-administrator cannot rewrite
every system DACL; an actually unreachable tool fails inside AppContainer and is never retried under
the unrestricted host identity.

Unix-like shell:

```sh
./gradlew check
```

The build treats Java compiler warnings as errors and runs Checker Framework nullness analysis
across production and test sources. Java contracts use explicit `@NonNull`; unannotated reference
types are treated as nullable by the repository-wide Checker configuration.

Useful focused commands:

```powershell
.\gradlew.bat :veto-core:test
.\gradlew.bat :veto-protocol:test
.\gradlew.bat :veto-terminal:build
```

## Run locally

### 1. Prepare PostgreSQL

The checked-in defaults expect:

```text
URL:      jdbc:postgresql://localhost:5432/veto
user:     veto
password: empty
```

For another database, provide normal Spring Boot datasource properties through environment
variables, an external configuration file, or command-line properties. Schema management currently
uses Hibernate `ddl-auto=update`; treat that as a development default, not a production migration
strategy.

### 2. Start the backend

```powershell
.\gradlew.bat :veto-core:bootRun
```

Default listeners:

| Surface | Default |
| --- | --- |
| REST and application WebSocket | `http://127.0.0.1:8443` |
| terminal IPC | `tcp://127.0.0.1:5555` |
| routing WebSocket service | port `9090`, path `/veto/bus` |
| gRPC routing service | port `9091` |

On first use, create the initial administrator through the UI or `/api/auth/setup`. The default
signup mode is `invite`; anonymous signup remains closed after bootstrap unless configuration is
changed.

### 3. Start the terminal

Run this in a second terminal. Pass the workspace explicitly so the backend does not infer the
terminal process directory:

```powershell
.\gradlew.bat :veto-terminal:run --args="--workspace D:\path\to\project"
```

Additional terminal options:

```text
--address, -a    backend ZeroMQ address; default tcp://127.0.0.1:5555
--workspace, -w  absolute workspace root
--debug, -d      verbose terminal logging
```

### 4. Start veto-ui

Clone and run [MidCoard/veto-ui](https://github.com/MidCoard/veto-ui) separately, then configure it
to use the backend port shown above. The UI source is intentionally not duplicated in this
repository.

## Local SLM screening

The default model location is `veto-core/models/veto-slm.gguf` relative to the core working
directory. Override it with `VETO_LLAMA_MODEL_PATH`:

```powershell
$env:VETO_LLAMA_MODEL_PATH = "D:\models\veto-slm.gguf"
.\gradlew.bat :veto-core:bootRun
```

`llama-server` must be resolvable from `PATH`. At startup, verify the log contains both the selected
model path and `model loaded`. A configured path alone does not prove that semantic screening is
active. If llama.cpp is absent, Veto continues with deterministic screening and the documented
degraded SLM result.

The local model is currently advisory and experimental. Evaluation has shown false `LOW`
relevance classifications on legitimate code-reading and test commands, so production policies
must not assume that its relevance judgment is infallible.

## Main configuration

Defaults are defined in `veto-core/src/main/resources/application.yml`.

| Property or environment variable | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8443` | REST server port |
| `veto.terminal.bind-address` | `tcp://127.0.0.1:5555` | terminal IPC bind address |
| `VETO_DEPLOYER_POLICY` | `FULL_ACCESS` | `FULL_ACCESS`, `PROTECTED`, `SANDBOXED`, or `TENANT` |
| `veto.security.screening-mode` | `STRICT` | `STRICT`, `BALANCED`, or `BYPASS_ALL` |
| `VETO_SIGNUP_MODE` | `invite` | `solo`, `public`, or `invite` |
| `VETO_MEMORY_STORE` | `jpa` | `memory`, `jpa`, `vector`, or `pgvector` |
| `VETO_VAULT_HOME` | `~/.veto` | vault and per-user state root |
| `VETO_LLAMA_MODEL_PATH` | `./models/veto-slm.gguf` | local gateway model |
| `veto.workspace.roots` | empty | deployer-authorized roots and the fallback Workspace |
| `veto.workspace.path-mode` | `REAL` | `REAL` or `VIRTUAL` path presentation |
| `veto.bus.websocket.allowed-origin-patterns` | local origins | allowed WebSocket UI origins |
| `veto.breaker.max_calls_per_episode` | `50` | per-agent tool-call episode limit |

`FULL_ACCESS` is convenient for local development but does not protect configuration or secret
files from the agent. Use a narrower deployer policy and explicit workspace roots for meaningful
path confinement.

Under `SANDBOXED`, every Session-declared root must resolve below one of
`veto.workspace.root/roots`. Under `TENANT`, it must additionally resolve below the direct
`<configured-root>/<username>` subtree. Admission resolves existing symbolic links before creating
a missing directory, so declaring a root cannot manufacture host ownership or escape through a
link. `FULL_ACCESS` and `PROTECTED` retain their documented host-path semantics.

## API groups

The backend currently exposes these primary REST groups:

- `/api/auth` — setup, login, logout, status, and user administration;
- `/api/sessions` — sessions, prompts, raw history, complete rewind-annotated records, pending vetoes, and
  background tasks;
- `/api/tasks` — asynchronous task lifecycle;
- `/api/patterns` — agent patterns;
- `/api/modeltiers` — model-tier profiles and bindings;
- `/api/vault` — encrypted note and credential storage;
- `/api/mcp/servers` — external MCP server discovery;
- `/api/fs` — filesystem browsing used by the UI;
- `/api/veto` — gateway status and payload checks;
- `/api/v1/training` — local training, quality, progress, evaluation, and deployment.

These are application APIs under active development, not a frozen public compatibility contract.

## Security boundaries

Veto currently provides several independent controls, but they have different assurance levels:

- **Tool arguments:** native arguments are schema-validated before invocation. Invalid or missing
  parameters are returned to the agent as actionable tool errors.
- **Gateway:** deterministic path/command classification establishes the safety floor; SLM results
  can increase danger and influence relevance but do not replace deterministic checks.
- **HITL:** calls outside the active auto-approval cells pause for a scoped user decision. Session
  grants can approve later calls with matching canonical shapes.
- **Vault:** provider credentials are stored per Veto user and resolved at the execution boundary.
- **Audit:** local records are hash-chained and tamper-evident. A local writable log cannot be
  described as tamper-proof against the same host user.
- **WebSocket:** the login token is validated during the handshake, origins are deployer-scoped,
  and each DeltaFrame is delivered only to connections owned by the frame's persisted Session
  owner.
- **Subprocess execution:** commands are passed as executable plus `argv[]`; Veto does not construct
  a shell command string. The child receives the host terminal environment, except sandbox-owned
  temp paths and deterministic no-color flags, and wall-clock timeouts are supported.
- **Windows kernel wall:** a named-event launch gate attaches the trusted bootstrap to a Job Object
  before target code can run. The Job enforces tree lifetime, aggregate memory, CPU, process-count,
  UI, and kill-on-close limits. The target then starts on a private Desktop in a per-workspace,
  zero-capability AppContainer. An inheritable workspace ACL allows workspace access; Windows still
  denies sibling reads/writes and loopback network access when Gateway policy is bypassed.
- **Other platforms:** macOS has a default-deny Seatbelt compatibility backend with protected
  workspace metadata, curated runtime rules, and no network grants, but still needs release-runner
  evidence. Linux uses Bubblewrap for read-only-root/workspace-write mounts plus user/PID/IPC/network
  namespaces, followed by an inner `no_new_privs`/seccomp stage. Linux refuses to run when a trusted
  `bwrap` executable is unavailable; bundled-helper packaging and cgroup resource limits remain.

The Windows AppContainer is an identity/capability boundary, not a container filesystem or Docker
image. Its reachable storage is the workspace plus the AppContainer's private profile storage;
zero capabilities deny direct network access. Host runtimes outside those locations are not made
reachable merely because an unrelated command was approved. Veto resolves and projects the selected
executable per invocation without a trusted-runtime list or setup step; an unreachable dependency
fails closed. Destination-scoped network prompts and an authenticated network broker are not
implemented. Windows currently supports a permit-scoped `network: true` capability grant after
Gateway/HITL approval; the default remains deny-network.

## Background tasks

`run_task` starts one direct executable in the session workspace. Output is continuously drained
into a bounded line buffer and can be inspected with `view_task`; `stop_task` terminates a task.
The backend also reports task exits to the owning agent on a later turn. A zero timeout means no
automatic lifetime cap, so session cleanup remains important.

## External MCP tools

External MCP servers are discovered through `/api/mcp/servers/discover` and translated into the
same `ToolDefinition` abstraction used by built-in tools. The Gateway screens remote calls before
dispatch. The current Java package name `agent.mcp` should not be read as proof that every built-in
tool is transported over MCP; see [Known package-structure debt](#known-package-structure-debt).

## AgentDojo evaluation

The bridge under `veto-core/benchmarks/agentdojo` runs the upstream AgentDojo `v1.2.2` workspace
suite through a live Veto session and exposes AgentDojo tools over MCP. The default command runs a
small two-user-task by two-injection-task integration subset; it is not the complete benchmark.

```powershell
$env:VETO_TEST_PASSWORD = "<test-user-password>"
veto-core\benchmarks\agentdojo\.venv\Scripts\python.exe `
  veto-core\benchmarks\agentdojo\veto_agentdojo_benchmark.py `
  --username <user> --pattern <pattern>
```

Use `--user-tasks all --injection-tasks all` for the full selected workspace matrix. Reports keep
baseline utility, attacked utility, attack success, and defense success separate. Do not compare a
small integration subset with a complete published benchmark score.

## Local release bundle

Build a versioned local distribution under the ignored `release/` directory:

```powershell
.\gradlew.bat localRelease
```

The bundle contains the executable core JAR, terminal distribution, launch scripts, license, and
version metadata. `release/` is generated output and must not be committed.

## Development rules

- Use `ignorekit.json` as the source of truth for ignore rules, then regenerate `.gitignore` with
  IgnoreKit.
- Keep generated releases, models, benchmark runs, training caches, and local audit/vault data out
  of version control.
- Preserve the explicit-nullness contract: reference returns and parameters are nullable unless
  they carry `@NonNull`; primitive types are outside that contract.
- Run focused tests while iterating and the complete `check` task before a release.
- Keep protocol compatibility logic in `veto-protocol`; `veto-terminal` must not depend on
  `veto-core`.

## License

Veto is licensed under the [GNU Affero General Public License v3.0 only](LICENSE).
