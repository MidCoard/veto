# Veto — Zero-Trust Agent Backend

Veto is a security-first agent backend that assumes cloud LLMs are untrusted actors. Every architectural component verifies, sandboxes, or redacts cloud instructions before they affect the local environment. The agent loop runs locally; the cloud provides reasoning — Veto controls the boundary.

This repository contains the Veto backend. For the frontend, see [veto-ui](https://github.com/MidCoard/veto-ui).

## How It Works

```
  Cloud LLM (untrusted)
        │
        ▼  reasoning only
 ───────┼──────────────── SECURITY BOUNDARY ────────────────
        │
  Veto Gateway ─── screens every tool call (deterministic rules + advisory SLM)
        │
  Agent Loop ───── guided or autonomous ReAct; in-loop routing (no pre-classifier)
        │
  Sandbox ──────── isolated execution (no shell; argv[] direct exec; cwd locked)
        │
  Vault ────────── just-in-time credential injection (secrets never enter agent context)
        │
  Shadow Audit ─── hash-chained, tamper-evident decision log
```

The **Veto Gateway** is the security controller: deterministic path/command validation runs first (authoritative), then an optional local SLM provides advisory relevance and danger judgments. CRITICAL calls are hard-denied — not executed, not user-approvable. Everything else is at most ASK (user-approvable).

## Architecture

Veto is organized into 15 parts. The full specification lives in [`ARCHITECTURE.md`](ARCHITECTURE.md); detailed lower-level designs in [`plans/mvp-core/`](plans/mvp-core/).

| Part | Responsibility | Status |
|------|---------------|--------|
| **1** Agent Loop | Guided + autonomous ReAct; in-loop routing; loop interception & drift detection | ✅ Shipped |
| **2** Delegations (Groups) | Leader-Mate topology; Execution DAG; Blackboard; iterative verification | ✅ Shipped |
| **3** Veto Gateway | Deterministic access control; SLM semantic interceptor; permission grants; output masking; ingress defense | ✅ Shipped |
| **4** Memory | Short-term buffer; Session/Cross-Session LTM; pgvector; VETO.md (The Law) | ✅ Shipped |
| **5** Agent & Arsenal | Persona; MCP tool foundation; skills; capability translator; sandbox | ✅ Shipped |
| **6** Local Training | Factory-trained SLMs; LoRA fine-tuning pipeline | ⏳ Beta |
| **7** Observability | Real-time event bus; shadow audit; resource telemetry | ✅ Shipped |
| **8** Transport | Interactive terminal (ZMQ); REST/WebSocket bridge; native bridge | ✅ Shipped |
| **9** Plugins | MCP registry; skill/persona directory; extension verification | ⏳ Beta |
| **10** Workspace | Multi-root workspace; VETO.md resolution; path virtualization | ✅ Shipped |
| **11** Advanced HITL | Guidance interrupt; strategic pivot; collaborative debugging | ⏳ Beta |
| **12** Runtime Profiles | Tiered performance profiles; dynamic concurrency; hardware-adapted inference | ✅ Shipped |
| **13** Distributed Swarm | Peer-to-peer delegation; CRDT Blackboard sync; distributed locking | ⏳ Future |
| **14** Model Tiers | Top/Mid/Low/Local 4-tier model selection | ✅ Shipped |

Implementation status is tracked per-page in [`plans/implementation-records/`](plans/implementation-records/).

## Key Design Decisions

**ReAct is the atom, always running.** The loop operates in guided mode (deterministic Actions Program IR) or autonomous mode (standard think→act→observe). The agent decides which mode in-loop — with full context, after investigation — not via a top-level classifier.

**One delegation construct: `create_group`.** The calling agent transforms into the Leader; it spawns Mates and orchestrates via the Blackboard. No separate evaluator-optimizer loop — that's a 2-node group with iterative verification.

**Deterministic floor is the security boundary.** Model-based controls (SLM relevance/danger, semantic masking) are advisory defense-in-depth. Hard guarantees live only in the deterministic Gateway rules, the Vault, and the Sandbox.

**Observations are data, never instructions.** Tool outputs and file content are delimited and tagged before re-entering the loop. An optional SLM flags injection patterns — advisory, layered on top of the deterministic framing.

## Project Structure

```
veto/
├── veto-protocol/          # Shared contract types + client-side interaction protocol
│   └── top.focess.veto
│       ├── contract/       #   IpcFrame, IpcClient, IpcMeta, ClientOptions
│       └── client.core/    #   ClientSession, ClientView, Theme, Logging
│
├── veto-core/              # Engine: agent loop, gateway, memory, sandbox, groups
│   └── top.focess.veto
│       ├── agent/          #   Agent, AgentRunner, AgentService, LoopBreaker
│       ├── agent/loop/     #   PromptCompiler, ActionsProgram, ResponseEnforcer
│       ├── agent/mcp/      #   McpEngine, ToolDefinition, NativeMcpTool, ToolSchemaCompiler
│       ├── agent/mcp/tools/#   Native tool implementations (run_command, file ops, grep…)
│       ├── agent/screening/#   Gateway, DangerComputation, ScreeningMode, DeployerPolicy
│       ├── agent/intercept/#   HitlRegistry, PermissionGrant, VetoOption, IngressDefense
│       ├── agent/identity/ #   AgentPersona, PersonaEntity
│       ├── agent/skills/   #   SkillRegistry, MarkdownSkillLoader
│       ├── agent/translation/ # CapabilityTranslator, VetoCapabilityTranslator
│       ├── agent/workspace/#   Workspace, PathResolver, VetoMdResolver
│       ├── agent/drift/    #   ReadHistory (lazy file-drift detection)
│       ├── agent/web/      #   Web fetch tools
│       ├── group/          #   Group, Blackboard, ExecutionDag, GroupOrchestrator, LlmLeader
│       ├── llm/            #   LLM clients (OpenAI, Anthropic, Gemini, DeepSeek)
│       ├── memory/         #   MemoryStore, JpaMemoryStore, PgvectorMemoryStore, MemoryTools
│       ├── sandbox/        #   SandboxManager, ConstrainedSubprocessSubstrate, KernelSandboxSubstrate
│       ├── vault/          #   Per-user credential isolation
│       ├── command/        #   CommandRegistry, VetoCommandSender
│       ├── observability/  #   Shadow audit, event bus
│       ├── veto/           #   Local SLM bridge (llama.cpp + GBNF)
│       └── model/tier/     #   Model tier registry
│
├── veto-terminal/          # Interactive terminal client (JLine + Mordant)
│   └── top.focess.veto
│       └── terminal/       #   IpcServer, MordantRenderer, MordantTheme
│
├── plans/                  # Design documents (untracked)
│   ├── mvp-core/           #   LLD pages per part
│   └── implementation-records/ # Build status per part
│
└── ARCHITECTURE.md         # High-level architectural requirements blueprint
```

## Prerequisites

- **JDK 21+** (LTS)
- **Gradle 8.10+** (or use the included wrapper)
- **PostgreSQL + pgvector** (for production memory store; in-memory reference included)
- **Optional:** `llama.cpp` server for local SLM (Part 3.2 / Part 3.3)

## Quick Start

```bash
# Build
./gradlew clean build

# Run (interactive terminal)
./gradlew :veto-terminal:bootRun
```

The terminal starts a local ZMQ-based IPC session. Type commands or natural-language prompts; the agent loop handles the rest.

## Configuration

Key configuration properties (in `application.yml`):

| Property | Default | Description |
|----------|---------|-------------|
| `veto.security.screening-mode` | `STRICT` | Gateway screening matrix: `STRICT`, `BALANCED`, or `BYPASS_ALL` |
| `veto.security.deployer-policy` | `PROTECT_SENSITIVE` | Path policy: `FULL_ACCESS`, `PROTECT_SENSITIVE`, `SANDBOXED`, `TENANT` |
| `veto.memory.store` | `in-memory` | Memory backend: `in-memory`, `jpa`, `pgvector` |
| `veto.workspace.roots` | — | Comma-separated project root paths |
| `veto.workspace.path-mode` | `REAL` | Path resolution: `REAL` or `VIRTUAL` |

## Security Model

- **Gateway (Part 3):** Every tool call is screened before execution. Deterministic rules classify paths and commands into SAFE / DANGEROUS / CRITICAL. CRITICAL = hard-deny. The screening mode controls which (relevance, danger) cells auto-approve.
- **Sandbox (Part 5.6):** `ConstrainedSubprocessSubstrate` — no shell, argv[] direct exec, cwd locked, env allowlist, timeout. Kernel-level walls (Windows Job Object, Linux cgroup) attach post-spawn.
- **Vault (Part 3.6):** Credentials injected just-in-time into the sandbox. Never written to workspace, never visible in agent context, never logged.
- **Permission Grants:** `accept_*_like_this` session grants convert ASK → APPROVE without re-prompting. Grants are scoped by canonical path prefix (reads/writes) or executable+subcommand+flag-shape (commands).
- **Output Masking:** Default-on `accept_and_mask` scrubs AWS keys, private keys, DB URLs, and password/token markers from tool output before it re-enters the loop.

## License

Project Veto is open-source software licensed under the
[GNU Affero General Public License v3.0 only](LICENSE).
