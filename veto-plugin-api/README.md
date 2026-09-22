# Veto plugin API — experimental

For building and running Veto, start with the [project README](../README.md).
This module is the plugin contract: the contribution registration model plus
the generic Java plugin lifecycle (the former veto-extension module is merged
into it). External Java-JAR activation is not available. For working script
plugins, use the separate [script runtime](../veto-plugin-runtime/README.md).

## Implemented behavior

- `ContributionPoint<T>` identifies a contract by namespaced ID, exact major
  version, Java class identity and cardinality.
- `Contribution<T>` supplies an implementation, local ID and within-point
  ordering constraints. The host supplies source identity and provenance.
- `ContributionCatalog.Builder` validates and stages contributions atomically
  per source. It rejects duplicate identities, incompatible contracts, missing
  ordering targets, cycles and cross-point ordering constraints.
- `freeze()` validates required points and catalog constraints, then returns an
  immutable registration snapshot. Implementation objects may still have state.
- The catalog does not instantiate plugins, grant permissions, start handlers or
  publish itself into a running application.

Registration provenance is not authority. A category or contribution never grants
access to a host service.

`PluginContributions` is a bounded immutable list of typed
`Contribution<?>` values. `PluginContext` carries identity metadata, a live
read-only lifecycle state via `state()`, a failure-reporting callback, and
host-granted services via `service(Class<T>)`; it does not issue permissions or
approved invocation services. Host services are host-granted authority —
registration never earns them, and a plugin must degrade when a service is
absent. The service map is a generic mechanism only: this module (like
veto-plugin-runtime and veto-core's public surface) carries no plugin-specific
API, so plugins stay portable to other agent clients; a plugin's own
host-service contracts live in that plugin's module.

`AbstractVetoPlugin` provides lifecycle callbacks without allocating threads or owning
lifecycle state. Veto's `PluginManager` discovers built-in plugins through
`ServiceLoader` (a public no-arg constructor is required) and owns one shared
control executor and each
plugin's `ManagedPlugin` handle. Handles serialize lifecycle transitions and invocation
admission; tool handlers run on caller threads. Closing a handle drains admitted calls
and releases the plugin's resources once. The manager shuts down the shared executor
after closing all handles.

The standalone fixture contributes a computation tool, a category, a static prompt
and an observation-text transformer. Its JAR does not bundle shared contracts.
Package tests load it with shared API class identities and without application
libraries. Classloader separation is not a security sandbox.

## Standard contribution points

Each standard contribution point has exactly one contract type; the point ID,
major version and contract class are fixed together. Plugins may define their
own points, but the host never invokes a point it does not define.

| Point | Contract type | Contribution |
|---|---|---|
| `veto:tools` | `Tool` | Tool object: description, effect, categories, and either a record- or schema-authored invocation |
| `veto:tool-categories` | `ToolCategory` | Display label and description |
| `veto:prompts` | `PromptContribution` | Static package resource path (reserved; fixture-only consumer today) |
| `veto:frontend` | `FrontendContribution` | Browser ESM activation, React registrations and scoped JSON actions |
| `veto:observation-middleware` | `ObservationMiddleware` | Observation-text transform; chained by the host as the session-less masking hook |
| `veto:input-protection` | `InputProtection` | User-prompt transform before the prompt enters session history |
| `veto:file-protection` | `FileProtection` | File-content transform at `view_file` capture |
| `veto:file-observation` | `FileObservation` | `view_file` observation transform before history, preserving SECRET_REF markers |
| `veto:session-lifecycle` | `SessionLifecycle` | Owner/session/agent lifecycle notifications (default no-op methods) |

The three protection points all extend `TextProtection` (`transform(scope,
sourceId, text)`), but each has its own contract type so a contribution is bound
to exactly one invocation site. `ObservationMiddleware` and `SessionLifecycle`
are session-less: observation transforms carry text and a cancellation signal,
and lifecycle notifications carry plain owner/session/agent IDs. Masking
semantics belong to the contributing plugin; there is no shared mask contract.

`Tool` is a sealed contract with two authoring shapes, mirroring how the host
models its own tools:

- `Tool.RecordTool` — an in-process Java plugin declares its arguments as a
  plain Java record (`argsType()`) and the host reflects it into the input
  schema, validates the call, deserializes the arguments, and serializes the
  typed result back to JSON. This is the same record-authored shape built-in
  native tools use, so no hand-written JSON schema is involved.
  `RecordToolContribution` is the stock carrier.
- `Tool.SchemaTool` — a portable or out-of-process plugin declares explicit
  `inputSchema()`/`outputSchema()` JSON and exchanges `JsonValue`; this is the
  only form a non-Java host process (the script runtime) can consume.
  `ToolContribution` is the stock carrier.

`Tool.Effect` is generic: `PRIVILEGED` marks a tool that crosses a
host trust boundary (the host gates every call with explicit approval; no
path/command/URL arguments), `COMPUTATION` and `EXTERNAL_UNKNOWN` stay ordinary
external effects.

`Cancellation` carries a cancellation signal, not an approved invocation.
These contracts are experimental.

## Production integration

`ToolEngineImpl` consumes immutable snapshots through the internal `ToolCatalog`
adapter. Spring still discovers built-in beans. Startup validates the complete
native/internal-agent batch before publishing it; initialization is one-shot.

MCP discovery remains dynamic. Successful additive batches publish a new snapshot;
conflicting batches leave the previous one intact. Existing definition identities
are preserved. Dispatch pins the resolved registration and retains the existing
caller and full-call authorization checks.

`RegisteredTool` and the private `veto:runtime-tools` point are host implementation
details, not public plugin contracts. The [script runtime](../veto-plugin-runtime/README.md) adds operator-configured plugin
tools through this catalog. Host-granted services reach plugins through
`PluginContext`; generic model hooks are not implemented.

## Executable browser plugins

`StandardContributionPoints.FRONTEND` accepts `FrontendContribution(module, handler)`.
The module is self-contained browser ESM exporting `activate(host)`. Package it
as a plugin resource and read its source when registering. Java supplies loading,
registration, lifecycle admission, and authenticated actions; browser UI logic
lives in JavaScript, or TypeScript/JSX compiled to JavaScript.

The host provides its React instance, `registerReferenceRenderer(type, Component)`,
`registerPanel(id, "conversation.footer", Component)`, and a lifecycle abort
signal. `activate` optionally returns a disposer. Registrations are scoped to the
selected session/agent and UI surface. Components receive the reference where
applicable, locale, session, agent, and `context.invoke(action, arguments, signal)`.
They can use React hooks, custom elements and event handlers.

`GET /api/sessions/{name}/plugin-frontend` returns the selected modules.
`POST /api/sessions/{name}/plugin-frontend/actions` accepts `moduleId`, `agentId`,
`action`, and JSON-object `arguments`. The owning plugin's handler receives the
authenticated owner/session/agent scope and returns `JsonValue`; resource-level
access checks belong to the handler. These actions are outside model tools and
history. Responses are not cached. Browser code is trusted application code,
not a sandbox, and must use the supplied React instance instead of bundling its
own. Other dependencies must be bundled; relative/bare module imports are not
resolved. Secret protection uses this executable frontend API for its own reveal
component and keeps its reveal/reset behavior in the plugin's JavaScript resource.
The veto-ui plugin loader for this surface is still pending; the Java contract
stays.

## Build and verify

Run from the repository root:

```sh
./gradlew :veto-plugin-api:test
./gradlew :veto-plugin-fixture:pluginPackage
```

The fixture is generated at
`veto-plugin-fixture/build/plugin/top.focess.fixture/0.1.0/`, with its own README.
Building or copying this package does not activate it in Veto.

## Limitations

The manifest and Java SPI are experimental. Java package activation, portable model hooks and cross-client adapters are not
implemented by this module. The separate script runtime supports a different
manifest and validates its own descriptors and messages. The fixture demonstrates lifecycle and registration mechanics,
not production protection or plugin installation.
