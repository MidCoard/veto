## Workflow-core API (2026-09-23)

Shared Java contracts now live under `top.focess.veto.api.*`. This is a Java binary/source
compatibility change: rebuild existing Java plugins with the new imports and rename
the ServiceLoader descriptor to
`META-INF/services/top.focess.veto.api.plugin.VetoPlugin`. Plugin authors depend
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

Plugins publish services through `StandardContributionPoints.SERVICES` and discover
named/versioned handles through `PluginContext.services()`. Inputs and outputs are
`JsonValue`; consumers import only veto-api, never a provider class or a feature-specific
Java interface. See the named-services example below. DuckDuckGo and Brave ship in
builtin and use this same mechanism.

Model-provider, storage and other feature contracts still in core are migration gaps; this
release does not yet make every feature implementable through the API alone.

# Veto API — experimental

For building and running Veto, start with the [project README](../README.md).
This module is the shared authoring contract that both `veto-core` and every
plugin compile against: the in-process tool-authoring surface
(`CapabilityTool`, `ToolCapability`, the `@ToolSecurity`/`@ToolDoc`/`@Doc`
annotations and their supporting types), the contribution registration model,
and the generic Java plugin lifecycle (the former veto-extension module is
merged into it). External Java-JAR activation is not available. For working
script plugins, use the separate [script runtime](../veto-plugin-runtime/README.md).

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
absent. The class-keyed host service map is separate from `services()`, the named JSON
service directory used for communication between plugins. New plugin-owned services
do not require adding feature-specific Java types to veto-api.

`AbstractVetoPlugin` provides lifecycle callbacks without allocating threads or owning
lifecycle state. Veto's `PluginManager` discovers built-in plugins through
`ServiceLoader` (a public no-arg constructor is required) and owns one shared
control executor and each
plugin's `ManagedPlugin` handle. Handles serialize lifecycle transitions and invocation
admission; tool handlers run on caller threads. Closing a handle drains admitted calls
and releases the plugin's resources once. The manager shuts down the shared executor
after closing all handles.

The shipped implementations are [secret protection](../veto-secret-protection/README.md)
and [builtin tools and search providers](../veto-builtin/README.md), plus the
[built-in workspace tools](../veto-builtin/README.md). They register through ServiceLoader;
their tests exercise the actual plugin implementations. Web search compiles and runs
its module tests without core on the classpath. `veto-builtin` also depends only on
this API and owns eight workspace tools; other built-in families remain in core.

Workspace plugins implement `WorkspaceReadTool` or `WorkspaceWriteTool`. Host dispatch
passes a call-scoped capability to the typed `execute` method after authorization;
the one-argument method rejects execution without that capability. File handles and
workspace contracts live in `top.focess.veto.api.agent.capability`. Tool error codes,
exceptions, statuses and JSON result helpers now also belong to the API; update
imports when rebuilding existing Java tools.

Operators can map qualified contribution IDs to stable public names through
`veto.plugins.tool-names`. The bundled configuration preserves workspace tool names.
Aliases retain plugin provenance and do not bypass selection or execution permits.

## Standard contribution points

Each standard contribution point has exactly one contract type; the point ID,
major version and contract class are fixed together. Plugins may define their
own points, but the host never invokes a point it does not define.

| Point | Contract type | Contribution |
|---|---|---|
| `veto:search-providers` | `SearchProvider` | Named search backends, selected by host configuration and session bindings |
| `veto:workflow` | `WorkflowHook` | Session-scoped input, model, tool and observation callbacks |
| `veto:native-tools` | `CapabilityTool` | Record-authored Java tools |
| `veto:tools` | `Tool` | Tool object: description, effect, categories, and either a record- or schema-authored invocation |
| `veto:tool-categories` | `ToolCategory` | Display label and description |
| `veto:prompts` | `PromptContribution` | Reserved static resource descriptor; current MDC discovery uses classpath resources |
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

Plugins author tools in one of two ways, mirroring how the host models its own
tools:

- In-process JAR plugins contribute a `CapabilityTool<T>` (from
  `top.focess.veto.api.agent.tool`) through the `veto:native-tools` point. The tool
  declares its arguments as a plain Java record (`getArgsClass()`) and carries
  the same `@ToolSecurity`/`@ToolDoc` annotations a built-in native tool uses.
  The host reflects the record into the input schema, validates the call,
  deserializes the arguments, and executes the handler through its internal tool
  state exactly like a core native tool — no hand-written JSON schema and no
  out-of-process hop. Its declared capability selects the ordinary execution authorization boundary.
- Portable or out-of-process plugins contribute a `Tool` through the
  `veto:tools` point. `Tool` declares explicit `inputSchema()`/`outputSchema()`
  JSON and exchanges `JsonValue`; this is the only form a non-Java host process
  (the script runtime) can consume. `ToolContribution` is the stock carrier.

`Tool.Effect` is generic: `PRIVILEGED` marks a tool that crosses a
host trust boundary (the host applies approval-level screening), `COMPUTATION` and `EXTERNAL_UNKNOWN` stay ordinary
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
`PluginContext`; `WorkflowHook` supplies the session-scoped model/tool callbacks described above.

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
The veto-ui repository contains the loader and its tests; this backend milestone does not claim browser acceptance.

## Build and verify

Run from the repository root:

```sh
./gradlew :veto-api:test :veto-plugin-runtime:test :veto-secret-protection:test :veto-builtin:test
```

The built-in plugin JARs are packaged as dependencies in `:veto-core:bootJar`.

## Limitations

The manifest and Java SPI are experimental. This module does not install Java packages or provide adapters for other agent clients. The separate script runtime supports a different
manifest and validates its own descriptors and messages. Java plugins are currently
discovered from the application classpath; this is not an external JAR installer.

## LLM and delegation contracts

`top.focess.veto.api.llm` holds provider requests/results, messages, options,
provider adapters and the host MDC rendering port. Implement `LlmProvider` and
contribute it through `StandardContributionPoints.LLM_PROVIDERS` without a core
dependency. The production example is [veto-llm-providers](../veto-llm-providers/README.md).
Model tiers, local inference and gateway policy remain host responsibilities.

`DelegationCapability` and `DelegationTool` support agent tools through the same
plugin registration path. The built-in `create_group` implementation calls this
API; core supplies the authorized capability and owns the role transition.

## Plugin-authored planning

`api.agent.workflow` exposes the program/value contracts and `PlanExecution`
continuation callbacks. The built-in plugin owns parsing, plan-language validation
and interpretation; API-only plugins can supply their own continuation. Runtime
callbacks preserve host tool authorization, model budgets and cancellation.
`ResponseTool` receives a host `ResponseCapability` to submit a plan or a
cited answer. `ResponseSubmission` declares exclusive submission semantics by
metadata rather than a hard-coded tool name. `ContextualInputSchemaSource` supports
tool-owned schemas specialized against the live model-visible manifest.

## Complete tool capability surface

`HostCapabilityTool<T,C>` supports typed host injection for process execution,
background tasks, network egress, skills, user interaction, memory, monitors and
group control. Their contracts and shared process, interaction, group, memory and
skill values are API types. Plugins own execution behavior and use these ports for
authorized host effects. `ReaderSession.Factory` supplies plugin-owned private
reader tools and document state while the host runs the shared child-agent lifecycle.
`ReaderExecutionResult` marks outputs eligible to carry host-issued child identities.

## Named services between plugins

A provider returns a normal contribution during initialization:

```java
Contribution.of(StandardContributionPoints.SERVICES, "normalize",
    new ServiceRegistration("example:text-normalize", 1, request -> normalize(request)))
```

A consumer retains its `PluginContext` and invokes after activation:

```java
var handle = context.services().find("example:text-normalize", 1).orElseThrow();
JsonValue result = handle.invoke(new JsonValue.StringValue("input"));
```

`available()` exposes names, major versions and provider IDs. Lookup is exact; duplicate
name/version pairs fail activation, while different versions may coexist. The registry
is published after all initialization contributions are collected. Registration is
startup-only; invocation requires active caller and provider lifecycles and honors
current session selection when a session is present. A retained handle does not bypass
those checks. Handlers return bounded JSON values; safe `ServiceException` codes cross
the boundary, and unexpected implementation diagnostics are hidden. Class-keyed
`service(Class)` remains exclusively for host-granted Java capabilities.

Search is an example protocol: `veto.search:<provider>` version 1 accepts an object
with `query`, `allowedDomains`, `blockedDomains` and `maxResults`, and returns an array
of `{title, url, snippet}` objects. Domain lists may be null. A provider can implement
this JSON contract directly without importing any search implementation. `SearchServices`
and `SearchProvider` are optional authoring helpers, not registry dispatch types.

## Agent host contracts

Portable `AgentAction`, `AgentResult`, `AgentState`, `ToolCallEvent` and `ToolResultEvent`
live in `api.agent`; `LlmBinding` lives in `api.llm`; `PluginBinding` in `api.plugin`.
General cited-answer/plan submissions use `api.agent.response.ResponseRequest` and
`ResponseCapability`. `PlanStepContext` and `PlanExecution` describe the plan runtime
boundary. The builtin owns the plan loop and calls `Runtime.beforeStep()` before each
step, so host cancellation, budgets and sourced observations remain enforced.

Spring/session/database integration lives in core's `integration.plugins` package.
Generic lifecycle admission, service dispatch and managed plan execution belong in
veto-plugin-runtime. These host adapters are not plugin feature implementations.
