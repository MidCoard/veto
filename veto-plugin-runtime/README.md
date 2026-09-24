## Workflow-core API (2026-09-23)

Shared Java contracts now live under `top.focess.veto.api.*`. Plugin authors depend
on `veto-api`, never on `veto-core`. Java tools may implement `NativeTool` or
`AgentTool` and contribute through `StandardContributionPoints.NATIVE_TOOLS`.
All Java tools share registration and execution. Capabilities describe the operation;
plugin origin does not require PRIVILEGED or prohibit path/command/URL arguments.
Registration checks contract coherence, not handler fields. Java plugins are trusted
code; authority is enforced by call permits and scoped host services, not a Java sandbox.

`StandardContributionPoints.WORKFLOW` accepts `WorkflowHook`: input transformation,
before/after model callbacks, tool rejection/explicit approval, result transformation,
and observation transformation. Hooks run in catalog order for the selected pinned
session, with lifecycle admission and cooperative cancellation. A hook cannot override
a host refusal; requested approval is per call. Model callbacks expose model metadata
and response text, not provider secrets or mutable native-call state. Input hooks run
before input protection; output hooks precede final observation protection. Failures
stop the operation with a safe host error. Script workers do not support Java hooks.

Provider, storage and other feature contracts still in core are migration gaps; this
release does not yet make every feature implementable through the API alone.

# Script plugins — experimental

This module runs operator-configured JavaScript plugins through a bounded local
process protocol. It depends on the shared plugin contract module
([veto-api](../veto-api/README.md)) and Jackson, not Spring
or Veto core. The Veto adapter supplies startup configuration, tool registration,
approval checks and administrator diagnostics.

## Run the example with Veto

Install Node.js and JDK 25. Prepare Veto's database and configuration as described
in the [project README](../README.md). Run from the repository root, replacing the
example absolute paths with paths on your machine:

```sh
sh ./gradlew :veto-core:bootRun --args='--veto.plugins.trusted-code=true --veto.plugins.node-command=/absolute/path/to/node --veto.plugins.paths=/absolute/path/to/veto/veto-plugin-runtime/examples/text-tools'
```

The [example package](examples/text-tools/plugin.json) and its
[worker](examples/text-tools/worker.mjs) register `plugin_text__length`. Ask a
standalone agent to use it to count Unicode code points in `a😀b`; the expected
result is `3`. The tool follows the ordinary Gateway/HITL approval flow and role
filters. Leader and mate roles do not automatically gain external-tool capabilities.

With a built executable JAR, pass the same properties as command-line arguments.
Set an absolute `VETO_AUDIT_DIR` as described in the release guide. No JAR plugin
classloader or MCP subprocess configuration is involved.

## Configuration and trust

| Property | Default | Meaning |
|---|---|---|
| `veto.plugins.paths` | empty | Comma-separated absolute package directories; at most 16 |
| `veto.plugins.node-command` | empty | Absolute path to a Node executable; required when packages are configured |
| `veto.plugins.trusted-code` | `false` | Explicit operator acknowledgement that installed scripts run as the server user |
| `veto.plugins.script-mode` | `trusted` | `isolated` currently refuses configured script packages before loading; no verified strict OS launcher is available |
| `veto.plugins.timeout-ms` | `5000` | Deadline per protocol request, including initialization; 100–60000 ms |

**This is trusted local code, not a sandbox.** The worker can access resources
available to the server user. Approval gates tool invocations, not arbitrary code
executed by a trusted worker at startup or in the background. Install only reviewed
packages outside agent-writable workspaces. The inherited environment is cleared,
including `NODE_OPTIONS` and provider/vault variables; platform-supplied variables
may still exist. Environment clearing is not filesystem or network isolation.

Startup validates packages and publishes their tool descriptors without starting Node.
The manager gives each package its own lazy Node host. A host crash or timeout
fails that package; calls are not automatically retried. Separate processes limit
accidental lifecycle interference but do not restrict trusted code's OS access.
Selecting `isolated` never falls back to trusted execution, including when
`trusted-code=true`. The enforced read-only snapshot workflow is not implemented;
the mode currently fails closed on every platform. There is no automatic folder
discovery, installation endpoint, hot reload or worker restart. Plugin authors must
not assume that consecutive calls belong to the same user or session.

## Package format

`plugin.json` and a self-contained `.mjs` entry file form the package. This is Veto's
experimental format, not the Agent Plugins standard manifest or the Java fixture
manifest. Only the fields shown in the example are accepted. Unknown fields,
including unsupported hook/service declarations, fail loading.

- `schemaVersion`: integer `1`.
- `id`: lowercase identifier, at most 20 characters; unique across configured packages.
- `version`: three dot-separated numeric components.
- `entryPoint`: a simple `.mjs` filename inside the package, not a path.
- `tools`: 1–32 descriptors with `id`, `description`, `handler`, `inputSchema` and `outputSchema`.

Tool IDs have at most 32 characters; names exposed to the model are
`plugin_<package-id>__<tool-id>`. The named handler comes from the descriptor, never
from a model-supplied executable name. Dependencies and extra package files are not
copied; bundle implementation code into the entry file. Node built-in imports work.

Schemas support `type`, `description`, `enum`, `properties`, `required`,
`additionalProperties` and `items`. Object schemas require `properties` and
`additionalProperties: false`; input schemas must be objects. Other keywords are
rejected rather than silently ignored. Nesting and property counts are bounded.
The host validates arguments and results, and descriptors return copied schemas.

Manifest and script files are limited to 64 KiB each. Symbolic file entries are
rejected. The entry bytes are copied into a private temporary directory before
launch; subsequent edits to the source file do not change the running worker.
A digest of the loaded manifest/script identifies the snapshot for diagnostics;
it is not a signature or an authenticity check.

## Worker protocol

Use one JSON-RPC 2.0 object per line on stdin/stdout. Stdout is exclusively protocol;
stderr is discarded to avoid publishing unfiltered plugin diagnostics.

The host first sends `initialize` with `{"protocolVersion":1}`. Return a result
object with the same protocol version. For each tool call it sends `invoke` with
`handler` and `arguments`; return the tool's JSON result. Echo the exact numeric
request ID and `jsonrpc: "2.0"`. There are no host-service callbacks in this version.
See the example worker for the complete implementation.

Each request/response frame is bounded to 64 KiB, with strict JSON parsing and
bounded nesting. Invocations are serialized per worker. Timeouts, protocol errors
or invalid results retire that worker; later calls fail until Veto restarts. Late
responses cannot be reused for another request. Host errors omit raw worker
payloads. Shutdown closes workers and removes snapshots on a best-effort basis.

## Inspect and verify

`GET /api/plugins` requires administrator authentication using Veto's existing
session token. It returns package ID, version, digest, active status and tool names.
It does not expose paths or scripts and cannot execute tools.

Run from the repository root with Node available on `PATH`:

```sh
sh ./gradlew :veto-plugin-runtime:test
sh ./gradlew :veto-core:test --tests top.focess.veto.plugin.runtime.ScriptPluginBootTest
```

The tests exercise real workers, protocol failures, bounded timeouts, snapshot
behavior and full Veto startup with dispatch through its production tool engine.
The Boot test uses an isolated H2 database and temporary vault.

## Current limits

Tools are the only supported script contribution. Categories, prompt contributions,
model hooks, credential services and cross-harness adapters are not enabled by this
runtime. Existing Java lifecycle fixtures remain separate and are not activated by
this loader. Script tools have unknown effects and elevated default danger; a
manifest cannot lower their authority requirements by claiming to be computation-only.

Select plugins when creating a session. The selected IDs, versions and revisions are
persisted with that session and cannot be changed afterward. An empty selection enables
no plugins. Settings → Plugins lists installed packages and their tools and hooks;
it does not change existing sessions. Execution checks the session selection as well
as the normal approval permit. Package loading and worker startup follow host configuration.
If an installed package no longer matches a session's pinned revision, activation fails
rather than silently changing that session's implementation.

The built-in `top.focess.secret-protection` provider is an ordinary
ServiceLoader-discovered plugin using this same selection mechanism. It
registers the credential-import tool (effect `PRIVILEGED`), the typed
input/file-capture/file-observation protections, the session-less
`veto:observation-middleware` masking contribution and session-lifecycle
notifications. It obtains its vault access and its detection model as
host-granted services through `PluginContext`; without them, credential imports
fail at call time and detection degrades to its deterministic fallback. Script
packages currently support tools only.

## Host/runtime boundary

This module owns generic plugin lifecycle admission, named JSON service dispatch and
`ManagedPlanExecution`, which retains plugin admission while a submitted plan runs.
Spring configuration, session selection, JPA converters and authorization adapters live
in `veto-core` under `integration.plugins`; portable `PluginBinding` is a veto-api value.
