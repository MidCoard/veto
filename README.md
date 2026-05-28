# Project Veto — Zero-Trust Cloud-Edge Agent Client

**Version:** 1.0.0-SNAPSHOT  
**Status:** Baseline Implementation

## Overview

Project Veto is an **enterprise-grade, multi-tenant Agent Client** designed for highly sensitive development and engineering environments. It operates on a **Cloud-Edge Collaborative Topology** — leveraging cloud-based Top Models for advanced reasoning while retaining **absolute sovereignty** over physical execution and data exfiltration.

### The Veto Philosophy

Unlike traditional "dumb" AI clients that act as pass-through proxies for cloud APIs, Veto enforces a **Local SLM Veto Gateway** — an edge-deployed Small Language Model that acts as an **intent firewall and semantic redactor**, holding absolute **Veto Power** over any tool execution or data transmission.

## Architecture (9-Grid Component Topology)

| Quadrant | Components | Responsibility |
|----------|-----------|----------------|
| **I: Perception & Interaction** | **C1** UI & Presentation Engine, **C2** Memory & Context System | Frontend rendering, session/workspace management |
| **II: Networking & Open Ecosystem** | **C3** Communication & Routing Bus, **C4** MCP Extensibility Engine | WebSocket/gRPC cloud umbilical, plugin discovery |
| **III: Orchestration & Execution** | **C5** Swarm Lifecycle Orchestrator, **C6** Atomic Tool Execution Sandbox | Process management, sandboxed atomic operations |
| **IV: Zero-Trust Defense** | **C7** Local SLM Veto Gateway ⭐, **C8** Local Credential Vault, **C9** Observability & Shadow Audit | The Veto Core: redaction, encryption, tamper-proof audit |

## Standard Data Flow (The Veto Protocol)

```
Cloud Top Model
      ⬇  (via C3 Bus — sanitized only)
━━━━━━━━━━━━━━━━━━━━━  SECURITY BOUNDARY ━━━━━━━━━━━━━━━━━━━━━
      ⬇  (C7 Veto Gateway intercepts & redacts)
C6 Sandbox ← injects → C8 Credential Vault
      ⬇
C5 Swarm Orchestrator
      ⬇
C2 Memory / C1 UI (HITL approval)
      ⬇
C9 Shadow Audit (logs pre/post diff)
━━━━━━━━━━━━━━━━━━━━━  OUTPUT TO CLOUD ━━━━━━━━━━━━━━━━━━━━━
```

## Project Structure

```
D:\ideaProjects\Veto\
├── ARCHITECTURE.md          # System architecture document
├── README.md                # This file
├── .gitignore
│
├── veto-client-core/        # Java/Spring Boot 17 (C3-C9)
│   ├── build.gradle.kts      # Gradle build (Kotlin DSL)
│   ├── settings.gradle.kts  # Gradle settings
│   ├── gradlew              # Gradle wrapper (Unix)
│   ├── gradlew.bat          # Gradle wrapper (Windows)
│   ├── gradle/wrapper/      # Gradle wrapper JAR & properties
│   ├── src/
│   │   ├── main/java/top/focess/veto/
│   │   │   ├── VetoApplication.java
│   │   │   ├── bus/         # C3: WebSocket, gRPC, heartbeat, reconnection
│   │   │   ├── mcp/         # C4: MCP server registry, WASM sandbox
│   │   │   ├── orchestrator/ # C5: Swarm, workers, circuit breaker, file locks
│   │   │   ├── sandbox/     # C6: Atomic capabilities (read_file, compile, etc.)
│   │   │   ├── veto/        # C7: Veto Gateway, llama.cpp bridge, GBNF, redactor
│   │   │   ├── vault/       # C8: AES-256-GCM credential vault, injection service
│   │   │   └── observability/ # C9: Tamper-proof audit, chain verification
│   │   ├── main/resources/
│   │   │   ├── application.yml
│   │   │   └── logback-spring.xml
│   │   └── test/java/top/focess/veto/
│   ├── mcp/servers/          # MCP server definitions
│   └── grammars/             # GBNF grammars for SLM
│
└── veto-ui/                 # TypeScript/React (C1-C2)
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.js
    ├── src/
    │   ├── main.tsx
    │   ├── App.tsx
    │   ├── components/
    │   │   ├── StreamingMarkdown.tsx
    │   │   ├── CodeHighlight.tsx
    │   │   ├── HITLApprovalCard.tsx
    │   │   ├── VetoStatusBar.tsx
    │   │   └── SessionSidebar.tsx
    │   ├── context/
    │   │   ├── types.ts
    │   │   ├── SessionManager.ts (C2)
    │   │   ├── WorkspaceTree.ts (C2)
    │   │   ├── PreferencesVector.ts (C2)
    │   │   └── useSession.ts
    │   └── services/
    │       └── WebSocketService.ts
    └── public/
```

## Prerequisites

- **JDK 17+** (OpenJDK or Oracle JDK)
- **Node.js 20+** and **npm 10+**
- **Gradle 8.10+** (or use the included Gradle wrapper `gradlew` / `gradlew.bat`)
- **Optional:** `llama.cpp` for C7 Local SLM (fallback works without it)

## Quick Start

### 1. Build the Core Engine (veto-client-core)

```bash
cd veto-client-core
.\gradlew.bat clean build
```

Run the core server:

```bash
.\gradlew.bat bootRun
```

By default the core starts on:
- **HTTP API:** `http://localhost:8443`
- **WebSocket Bus:** `ws://localhost:9090/veto/bus`
- **gRPC:** `localhost:9091`

### 2. Run the UI (veto-ui)

```bash
cd veto-ui
npm install
npm run dev
```

The UI runs on `http://localhost:5173` (proxied to the core at `:8443`).

### 3. Optional: Enable SLM Veto Gateway

Place a quantized GGUF model (1B-3B params) at `veto-client-core/models/veto-slm.gguf` and set:

```bash
set VETO_LLAMA_MODEL_PATH=./models/veto-slm.gguf
```

The gateway will automatically use llama.cpp for semantic redaction when the model is present.

## Running Tests

### Java Core Tests

```bash
cd veto-client-core
.\gradlew.bat test
```

Key test classes:
- `model/` — Domain model unit tests
- `veto/VetoGatewayTest.java` — C7 Veto Gateway redaction tests
- `veto/SemanticRedactorTest.java` — Pattern-based secret detection
- `veto/GBNFGrammarEngineTest.java` — Grammar management
- `orchestrator/CircuitBreakerTest.java` — Fault tolerance
- `vault/SecureStoreTest.java` — Encrypted credential storage
- `observability/DiffCalculatorTest.java` — Pre/post redaction diffing

### UI Tests

```bash
cd veto-ui
npm test
```

## Implementing the Veto SLM

To enable the full Veto Gateway (C7):

1. Install `llama.cpp` (or `llama-server`)
2. Download a quantized GGUF model (recommended: Qwen2.5-1.5B-Instruct-Q4_K_M or Phi-3-mini)
3. Place at `veto-client-core/models/veto-slm.gguf`
4. Restart the core

Without the SLM, the gateway operates in **deterministic-only mode** using regex-based redaction — still effective for secrets/IPs but without semantic structural enforcement.

## Credential Vault Setup

The C8 Credential Vault encrypts all secrets with AES-256-GCM:

```bash
# Set a master key (32+ chars recommended)
set VETO_VAULT_KEY=your-strong-master-password-here

# Vault auto-creates at start in ./vault/credentials.enc
```

**Credentials never traverse the C3 Communication Bus** — they are injected directly into the C6 Sandbox for the duration of tool execution only.

## Configuration Reference

All configuration is in `veto-client-core/src/main/resources/application.yml`.

| Prefix | Key | Default | Description |
|--------|-----|---------|-------------|
| `veto.bus.websocket` | `port` | `9090` | WebSocket bus port |
| `veto.bus.websocket` | `heartbeat-interval-ms` | `30000` | Heartbeat interval |
| `veto.veto-gateway` | `enabled` | `true` | Enable C7 Veto Gateway |
| `veto.veto-gateway` | `redact-secrets` | `true` | Enable secret redaction |
| `veto.veto-gateway` | `enforce-structural-constraints` | `true` | Enforce schema rules |
| `veto.orchestrator` | `max-workers` | `4` | Max swarm workers |
| `veto.orchestrator` | `circuit-breaker-threshold` | `3` | Failures before tripping |
| `veto.vault` | `master-key-env` | `VETO_VAULT_KEY` | Env var for vault key |
| `veto.observability` | `tamper-proof` | `true` | Enable hash-chain audit |

## Security Features

- **C7 Veto Gateway:** Local SLM with GBNF grammar-constrained decoding
- **Deterministic Secret Redaction:** IPv4/6, API keys, SSH keys, emails, DB URLs
- **Proprietary Parameter Protection:** Custom regex patterns for physics/engineering data
- **Structural Constraint Enforcement:** Validates output conforms to project rules
- **AES-256-GCM Credential Vault:** PBKDF2-derived keys, encrypted at rest
- **Tamper-Proof Audit Chain:** SHA-256 hash-linked audit records
- **Circuit Breaker:** Prevents cascading failures in the swarm
- **Path Traversal Protection:** All file operations sandboxed to working directory
- **No Generic Shell Execution:** Only atomic, predefined capabilities allowed

## License

Proprietary — Project Veto. All rights reserved.
