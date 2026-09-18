# Script plugins — experimental

This module runs operator-configured JavaScript plugins through a bounded local
process protocol. It depends on the shared extension layer and Jackson, not Spring
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
| `veto.plugins.timeout-ms` | `5000` | Deadline per protocol request, including initialization; 100–60000 ms |

**This is trusted local code, not a sandbox.** The worker can access resources
available to the server user. Approval gates tool invocations, not arbitrary code
executed by a trusted worker at startup or in the background. Install only reviewed
packages outside agent-writable workspaces. The inherited environment is cleared,
including `NODE_OPTIONS` and provider/vault variables; platform-supplied variables
may still exist. Environment clearing is not filesystem or network isolation.

Startup validates all packages and initializes their workers before tool publication.
A configured package that cannot start fails Veto startup. There is no automatic
folder discovery, installation endpoint, hot reload or worker restart. Each package
has one persistent worker shared across calls; plugin authors must not assume that
consecutive calls belong to the same user or session.

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
