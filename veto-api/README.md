# Veto API — experimental

`veto-api` is the public Java contract shared by the Veto host and trusted in-process
plugins. Plugin code depends on this module, not `veto-core`. For portable script plugins,
see the separate [script runtime](../veto-plugin-runtime/README.md).

The API is experimental. Rebuild plugins when its Java contracts change. An installed
Java package names its `VetoPlugin` subclass in `plugin.json`; that class must have a
public `(PluginContext, JsonValue.ObjectValue)` constructor. The host discovers Java
plugins only from installed package directories and constructs them after binding
their context and configuration.

## Choose the right boundary

| API | Purpose | Authority and availability |
|---|---|---|
| `PluginContributions` | Publish tools, hooks, named services, and other implementations | Registration only; acceptance requires activation validation and grants no permission. |
| `context.service(SomeType.class)` | Obtain an optional Java capability supplied by the host | Host-granted authority keyed by exact Java class identity; calls remain subject to current admission and authorization. |
| `context.services()` | Find a named, versioned JSON protocol implemented by another plugin | Plugin-to-plugin communication using only `JsonValue`; the directory is populated after initialization. |
| `PluginHost` | Request host-mediated invocation facts, waits, wake hints, events, and invalidation | Each operation applies its own lifecycle, selection, invocation, and authorization checks. |
| `context.storage()` | Access this plugin's application, user, or session namespace | Scoped persistence using host-issued scopes; old handles are revalidated and may be revoked. |

Java plugins run as trusted code in the Veto JVM. These APIs make host decisions explicit,
but they do not sandbox arbitrary Java, remove ambient JVM access, or provide OS process
isolation. A contribution declaration is never proof that an implementation is confined.

## Lifecycle and admission

The host discovers the entry metadata, binds the plugin-specific context and configuration,
and constructs the plugin on its lifecycle executor. It then reads the required
`contributions()` batch. Construction must not start threads or perform external effects.
All plugins finish contribution staging before the host binds the named service directory.
A provider therefore registers `SERVICES` in `contributions()`, while a consumer calls
`services().find(...)` only in `start` or later. After catalog validation, `start()` makes
the plugin ready and the host publishes its contributions.

An administrator may later disable an installed package, withdrawing its contributions
and draining calls before `close()`, or enable it by loading a fresh instance from its
package. `preferredToolName` lets a plugin request a stable public tool name; an
operator alias takes precedence and a collision rejects activation.

A plugin that cannot apply to this host may throw `PluginDeclinedException`
from its constructor or `contributions()` with a bounded public reason. The host closes it and
reports `DECLINED`; no contributions are published. It must not use this to
mask invalid configuration or callback bugs. A decline from `start()` is not
supported because the contribution catalog has already been validated.

Contribution handlers run on their caller's thread unless their contract says otherwise.
Plugins own synchronization inside their implementations. Before shutdown, the host closes
admission and calls `stopping()` once so the plugin can cancel blocking waits. Previously
admitted calls may still drain. The host then calls `close()` once, including after partial
initialization or startup failure. `close()` releases owned resources and tolerates partial state.

`context.state()` is a live observation, not an admission token. Named service handles and
host services recheck current authority. A provider may be absent because it is not installed,
selected, compatible, or active, so discovery returns `Optional`. Retaining a handle does not
preserve access after either plugin loses admission. Treat
`ServiceException.Code.UNAVAILABLE` as current availability and apply a suitable fallback.

## Minimal named-service plugins

This complete provider publishes a versioned JSON protocol:

```java
package example;

import java.util.List;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceScope;

public final class TextProviderPlugin extends VetoPlugin {
    public TextProviderPlugin(
            PluginContext context, JsonValue.ObjectValue configuration) {}

    public PluginIdentity identity() {
        return new PluginIdentity("example.text", "1.0.0");
    }

    public PluginContributions contributions() {
        var service = new PluginService("example:text", 1, ServiceScope.GLOBAL) {
            public JsonValue invoke(ServiceCallContext caller, JsonValue request) {
                return request instanceof JsonValue.StringValue text
                        ? new JsonValue.StringValue(text.value().trim())
                        : JsonValue.NullValue.INSTANCE;
            }
        };
        return new PluginContributions(List.of(
                Contribution.of(StandardContributionPoints.SERVICES, "text", service)));
    }

    public void start() throws PluginFailure {}
    public void close() throws PluginFailure {}
}
```

This complete consumer discovers the exact protocol version after binding and imports no
provider implementation type:

```java
package example;

import java.util.List;
import java.util.Optional;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceException;

public final class TextConsumerPlugin extends VetoPlugin {
    private final PluginContext context;
    private Optional<PluginServices.Handle> textService = Optional.empty();

    public TextConsumerPlugin(
            PluginContext context, JsonValue.ObjectValue configuration) {
        this.context = context;
    }

    public PluginIdentity identity() {
        return new PluginIdentity("example.consumer", "1.0.0");
    }

    public PluginContributions contributions() {
        return new PluginContributions(List.of());
    }

    public void start() throws PluginFailure {
        textService = context.services().find("example:text", 1);
    }

    public JsonValue normalize(String input) throws ServiceException {
        if (textService.isEmpty()) return new JsonValue.StringValue(input);
        return textService.orElseThrow().invoke(new JsonValue.StringValue(input));
    }

    public void close() throws PluginFailure {
        textService = Optional.empty();
    }
}
```

The provider needs no host capability merely to register a service. A real protocol should
document its JSON request, response, and public error codes. Do not retain context or service
handles after `close()`.

## Contributions

`ContributionPoint<T>` fixes a namespaced ID, major version, Java contract class, and
cardinality. `Contribution<T>` supplies a local ID, implementation, and optional ordering
constraints within that exact point. The host supplies source identity and provenance. A
plugin returns one immutable batch from `initialize`; the host validates and stages it
atomically before publication.

The current `StandardContributionPoints` are:

| Constant | Point ID | Contract |
|---|---|---|
| `AGENT_CONFIGURATION` | `veto:agent-configuration` | `AgentConfiguration` |
| `AGENT_INBOX` | `veto:agent-inbox` | `AgentInbox` |
| `SERVICES` | `veto:services` | `PluginService` |
| `CONTRIBUTIONS` | `veto:contributions` | `ProtocolPointDefinition` |
| `LLM_PROVIDERS` | `veto:llm-providers` | `LlmProvider` |
| `LISTENERS` | `veto:listeners` | `Listener` |
| `MODEL_RESPONSE` | `veto:model-response` | `ModelResponsePolicy` |
| `FRONTEND` | `veto:frontend` | `FrontendContribution` |
| `DATA_LIFECYCLE` | `veto:data-lifecycle` | `DataLifecycle` |
| `TOOLS` | `veto:tools` | `AgentTool`, `NativeTool`, or `RemoteTool` (host validated) |
| `CATEGORIES` | `veto:tool-categories` | `ToolCategory` |
| `PROMPTS` | `veto:prompts` | `PromptContribution` |
| `OBSERVATION` | `veto:observation-middleware` | `ObservationMiddleware` |

Use `TOOLS` for record-authored in-process Java tools and schema-authored JSON tools.
`ToolContribution` is the stock `RemoteTool` implementation. Registration validates coherence;
execution still passes through selection, admission, cancellation, and resource checks.

`BeforeTextCommitEvent` identifies input, file-capture, and file-observation boundaries. Selected
listeners transform text synchronously before publication; a listener failure prevents publication.

## Model flow and inbox

The core default model flow runs with no plugin. An admitted control tool may call
`ControlHost.push(ModelFlow)` to select a plugin flow after its successful tool result.
The selected flow receives a request-bound `ModelFlow.Runtime` for protected input,
budgeted model generation, authorized tools, messages, and observations. It may push
a nested flow, pop its own top frame, or finish the current request. Finishing a
request and popping a flow are separate operations. The host bounds nesting and
rechecks the current request and plugin admission; keeping a runtime reference does
not preserve authorization. `submit_plan` pushes a plan flow and pops it at exit.

`AgentInbox` is separate: it supplies pending observations and delivery callbacks,
not model-flow control. Its durable continuation keys retain the existing
`plugin-work:` prefix for stored checkpoint compatibility. The current stack is
runner-local; durable session descriptors and restart reconstruction are still
pending, so plugins must not rely on a pushed flow surviving backend restart.

## Host services and PluginHost

`context.service(Class<T>)` returns an optional host object using exact class identity. It is
the entry point for typed Java boundaries when the host grants them. A plugin must degrade or
fail safely when an optional service is absent. A retained object cannot turn an expired call,
stopped plugin, or revoked resource grant into a valid operation.

`context.host()` returns a non-null `PluginHost` for a plugin that requires host-mediated
effects. It fails initialization when the host has not granted that service. Plugins for which
those effects are optional should keep using `context.service(PluginHost.class)`.

`PluginHost.invocation(tool)` derives owner, session, agent, request, and call facts from a
live authorized invocation; plugin-supplied identity text grants nothing. `await` attaches
foreground waiting to that invocation. `wake` is only a scheduling hint. `publish` and
`invalidate` require an authorized selected session. `whenReady` runs after host migrations,
but its callback receives no automatic authorization for later effects.

## Named services between plugins

`context.services()` is a class-independent directory of name and exact major-version pairs.
Requests and responses are bounded `JsonValue` trees. `available()` exposes protocol name,
version, scope, and host-attributed provider ID. Duplicate name/version pairs fail activation;
distinct major versions may coexist.

Each service registration declares `GLOBAL`, `USER`, or `SESSION`. Global handlers use
`handle.invoke(request)`. Scoped handlers use `handle.invoke(scope, request)` with a scope issued
to the calling plugin by `PluginStorage.currentUser()`, `currentSession()`, or authorized recovery.
The host validates that grant on every call and supplies a `ServiceCallContext` to the provider;
JSON identity fields confer no authority. A scoped call without a matching grant fails. The
provider receives the validated user/session identity and a newly issued scope bound to its own
`PluginStorage`; the caller's token is never transferred to it.

Lookup and invocation apply caller/provider lifecycle and current selection checks. A retained
handle pins a descriptor but bypasses no check; it does not retain provider implementation
objects after registration revocation. Consumers must treat absence or revocation as an
ordinary unavailable result and may rediscover later. A protocol's request and response
schema is shared by its authors under the stable name and major version; plugins do not
import one another's implementation classes or JARs. Expected public failures use
`ServiceException`; unexpected provider diagnostics are hidden by the host. Provider classes,
Java serialization, and arbitrary objects do not cross this boundary.

## Plugin-defined contribution groups

The standard `CONTRIBUTIONS` point registers a `ProtocolPointDefinition`. Its name must be
qualified by the defining plugin ID, and its entries use a bounded JSON object schema. Another
plugin can register a JSON entry with `Contribution.of(definition.point(), localId, value)` using
only `veto-api`. At `start()` or later, `context.contributions().entries(pointId, major)` returns
currently visible entries with host-attributed provider IDs. A missing or disabled point produces
an empty group; entries submitted while its defining plugin is absent remain dormant until a
compatible point is active. Discovery does not invoke an entry or grant service authority.

All tools use the standard `TOOLS` point and inherit the abstract `Tool` base. A concrete
tool extends exactly one abstract branch: record-authored `AgentTool` or `NativeTool`, or
schema-authored `RemoteTool`. The host rejects any other `Tool` subclass before publication.
All execution passes through the gateway.

## Scoped storage

`context.storage()` returns `PluginStorage` bound to the current plugin ID. Its application
store is scoped to that plugin and Veto installation. User and session stores require
host-issued scopes; caller-created identity strings grant nothing. `currentUser()` and
`currentSession()` require an authenticated invocation. `scopes(...)` lists only scopes the
host authorizes for this plugin's recovery or background work.

`put` is compare-and-set: a null expected revision inserts only when absent, while a non-null
revision must match. `delete` also requires the current revision. Conflicts are explicit, and
a recreated entry does not reuse its old revision. Plugins own document schema versions.

The host revalidates scope existence, ownership, plugin admission, and authorization on access.
Stopping, disabling, or uninstalling a plugin retains its data by default but revokes active
work and handles. Permanent owner/session deletion invalidates corresponding scopes. This API
does not promise a disabled-plugin purge management surface.

## Agent and frontend contracts

`plugin.agent` exposes session-bound child execution. Profiles declare intent; the host owns
identity, available tools, model selection, budgets, cancellation, recovery, and termination.
Host-issued storage scope is required to open a session. Isolated-agent support may be absent
and fails closed; the Java interface itself is not an OS sandbox.

`FrontendContribution` carries a browser ESM module and scoped JSON action handler. Browser
code is trusted application code. It must dispose owned registrations on teardown and gains no
backend authority by rendering a component.

## Source migration notes

The Java authoring API follows the version of the `veto-api` artifact. It does not expose a
separate SPI-version constant. Script manifests and frontend contribution protocols keep their
own independently validated version fields.

| Removed source API | Migration |
|---|---|
| `VetoPlugin.API_VERSION` | Depend on the intended `veto-api` artifact version; do not compare it with script or frontend protocol versions. |
| `PluginBinding.canonicalId(String)` | Treat `PluginBinding.id()` as the exact persisted identity. A renamed plugin declares its own former IDs through `VetoPlugin.historicalIds()`; the host resolves them generically and rejects collisions. |
| Unmanaged `PluginContext` convenience constructors | Hosts and tests must supply the failure reporter, live state reader, and exact host-service map explicitly. |
| `VetoRequest.responseSchema` and convenience constructors | Use `ResponseContract` for host-owned response validation and call the canonical constructor with explicit `nativeToolsEnabled` and contract values. |
| Four-argument `LlmOptions` constructor | Pass the optional context-window value explicitly as the fifth argument; use `null` for the portable default. |
| Three-argument `LlmClient.RawCompletion` constructor | Pass both native-state and native-call lists to the canonical constructor. |
| Four-argument `NativeToolState` constructor | Pass the provider and state-format version explicitly. Durable legacy payloads still default missing values through `fromPayload`. |

These are source compatibility breaks for Java plugins. Existing stored bindings retain their
original IDs; an installed plugin may keep them readable by declaring unique historical IDs.

## Build and verify

From the repository root, run `gradlew.bat :veto-api:check`. This compiles and tests the module
and requires warning-free generated Javadocs. Use only `top.focess.veto.api` types from plugin
code and keep host implementation types out of plugin artifacts.

Class literals can be passed directly as `Foo.class`. The build-time Veto nullness checker treats the class-literal expression as non-null while keeping the usual nullable defaults for other expressions. `ToolDocs` contains only tool documentation helpers.

Standard contribution points accept the complete instance of their abstract aspect class. Tools extend `Tool` through `AgentTool`, `NativeTool`, or `RemoteTool`; services extend `PluginService`. Frontend modules, providers, prompts, listeners, policies, and other standard aspects likewise extend their respective API class and are registered as those objects. A component providing multiple aspects registers a separate object for each role.
