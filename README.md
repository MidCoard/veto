# Project Veto — Zero-Trust Cloud-Edge Agent Backend

## Overview

Project Veto is the core backend engine for the **enterprise-grade, multi-tenant Agent Client**. It operates on a **Cloud-Edge Collaborative Topology**, providing the critical security and orchestration layers that ensure local data sovereignty while leveraging cloud reasoning.

This repository contains the **Veto Backend** (C3-C9). For the frontend presentation layer, please refer to the [Veto UI Repository](https://github.com/MidCoard/veto-ui).

### The Veto Philosophy

Unlike traditional AI clients, Veto enforces a **Local SLM Veto Gateway** (C7) — an edge-deployed Small Language Model that acts as an **intent firewall and semantic redactor**, holding absolute **Veto Power** over any tool execution or data transmission.

## Architecture (Backend Components)

| Component | Responsibility |
|-----------|----------------|
| **C3** Communication Bus | WebSocket/gRPC umbilical cord to the cloud |
| **C4** MCP Extensibility | Model Context Protocol engine & WASM sandboxing |
| **C5** Swarm Orchestrator | Local process manager and worker pool |
| **C6** Atomic Tool Sandbox | Strictly defined OS operations (no generic shell) |
| **C7** Veto Gateway ⭐ | SLM-powered semantic redaction & enforcement |
| **C8** Credential Vault | Local AES-256-GCM secret isolation |
| **C9** Shadow Audit | Tamper-proof, hash-chained observability |

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
[Veto UI] (HITL approval via API)
      ⬇
C9 Shadow Audit (logs pre/post diff)
━━━━━━━━━━━━━━━━━━━━━  OUTPUT TO CLOUD ━━━━━━━━━━━━━━━━━━━━━
```

## Project Structure

```
D:\ideaProjects\Veto\
├── build.gradle.kts      # Gradle build (Kotlin DSL)
├── settings.gradle.kts   # Gradle settings
├── gradlew               # Gradle wrapper (Unix)
├── gradlew.bat           # Gradle wrapper (Windows)
├── gradle/wrapper/       # Gradle wrapper JAR & properties
├── src/
│   ├── main/java/top/focess/veto/
│   │   ├── VetoApplication.java
│   │   ├── bus/          # C3: Communication & Routing
│   │   ├── mcp/          # C4: MCP Engine
│   │   ├── orchestrator/  # C5: Swarm Lifecycle
│   │   ├── sandbox/      # C6: Atomic Execution
│   │   ├── veto/         # C7: Veto Gateway (SLM)
│   │   ├── vault/        # C8: Credential Isolation
│   │   └── observability/ # C9: Shadow Audit
│   └── main/resources/
│       ├── application.yml
│       └── logback-spring.xml
├── mcp/servers/          # MCP server definitions
├── grammars/             # GBNF grammars for SLM
└── models/               # Local SLM models (GGUF)
```

## Prerequisites

- **JDK 21+** (LTS)
- **Gradle 8.10+** (or use the included wrapper)
- **Optional:** `llama.cpp` server for C7 Local SLM

## Quick Start

### 1. Build and Run

```bash
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

### 2. Enable SLM Veto Gateway

Place a quantized GGUF model (1B-3B params) at `models/veto-slm.gguf`. The gateway will automatically use llama.cpp for semantic redaction when the model is detected.

## API Endpoints

- `POST /api/veto/process` — Process payload through Veto Gateway
- `POST /api/tool/execute` — Submit tool execution request
- `GET /api/tool/capabilities` — List available atomic capabilities
- `POST /api/tasks` — Create a DAG task
- **WebSocket Bus:** `ws://localhost:8443/ws/veto/bus`

## Security Features

- **C7 Veto Gateway:** Local SLM with GBNF grammar-constrained decoding
- **AES-256-GCM Credential Vault:** Credentials never leave the local machine
- **Tamper-Proof Audit Chain:** SHA-256 hash-linked audit records
- **Sandbox Isolation:** No generic shell execution; path traversal protection

## License

Proprietary — Project Veto. All rights reserved.
