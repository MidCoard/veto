# Veto API — experimental

`veto-api` is the public Java contract shared by the Veto host and trusted in-process
plugins. Plugin code depends on this module, not `veto-core`. For portable script plugins,
see the separate [script runtime](../veto-plugin-runtime/README.md).

The API is experimental. Rebuild plugins when its Java contracts change. A Java plugin has
a public no-argument constructor and a service-loader entry at
`META-INF/services/top.focess.veto.api.plugin.VetoPlugin`.

## Choose the right boundary

| API | Purpose | Authority and availability |
|---|---|---|
| `PluginContributions` | Publish tools, hooks, named services, and other implementations | Registration only; acceptance requires startup validation and grants no permission. |
| `context.service(SomeType.class)` | Obtain an optional Java capability supplied by the host | Host-granted authority keyed by exact Java class identity; calls remain subject to current admission and authorization. |
| `context.services()` | Find a named, versioned JSON protocol implemented by another plugin | Plugin-to-plugin communication using only `JsonValue`; the directory is populated after initialization. |
| `PluginHost` | Request host-mediated invocation facts, waits, wake hints, events, and invalidation | Each operation applies its own lifecycle, selection, invocation, and authorization checks. |
| `context.storage()` | Access this plugin's application, user, or session namespace | Scoped persistence using host-issued scopes; old handles are revalidated and may be revoked. |

Java plugins run as trusted code in the Veto JVM. These APIs make host decisions explicit,
but they do not sandbox arbitrary Java, remove ambient JVM access, or provide OS process
isolation. A contribution declaration is never proof that an implementation is confined.

## Lifecycle and admission

The host constructs the plugin, reads `identity()`, and calls `initialize(context,
configuration)` on its lifecycle executor. Initialization stages contributions and captures
dependencies; it must not start threads or perform external effects. All plugins finish
initialization before the host binds the named service directory. A provider therefore
registers `SERVICES` during `initialize`, while a consumer calls `services().find(...)` only
in `start` or later. After catalog validation, `start()` makes the plugin ready and the host
publishes its contributions.

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
import top.focess.veto.api.plugin.service.ServiceRegistration;

public final class TextProviderPlugin implements VetoPlugin {
    public PluginIdentity identity() {
        return new PluginIdentity("example.text", "1.0.0");
    }

    public PluginContributions initialize(
            PluginContext context, JsonValue.ObjectValue configuration) {
        var service = new ServiceRegistration(
                "example:text", 1,
                request -> request instanceof JsonValue.StringValue text
                        ? new JsonValue.StringValue(text.value().trim())
                        : JsonValue.NullValue.INSTANCE);
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

public final class TextConsumerPlugin implements VetoPlugin {
    private Optional<PluginContext> context = Optional.empty();
    private Optional<PluginServices.Handle> textService = Optional.empty();

    public PluginIdentity identity() {
        return new PluginIdentity("example.consumer", "1.0.0");
    }

    public PluginContributions initialize(
            PluginContext context, JsonValue.ObjectValue configuration) {
        this.context = Optional.of(context);
        return new PluginContributions(List.of());
    }

    public void start() throws PluginFailure {
        textService = context.orElseThrow().services().find("example:text", 1);
    }

    public JsonValue normalize(String input) throws ServiceException {
        if (textService.isEmpty()) return new JsonValue.StringValue(input);
        return textService.orElseThrow().invoke(new JsonValue.StringValue(input));
    }

    public void close() throws PluginFailure {
        textService = Optional.empty();
        context = Optional.empty();
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
| `AGENT_WORK` | `veto:agent-work` | `AgentWorkSource` |
| `SERVICES` | `veto:services` | `ServiceRegistration` |
| `LLM_PROVIDERS` | `veto:llm-providers` | `LlmProvider` |
| `WORKFLOW` | `veto:workflow` | `WorkflowHook` |
| `MODEL_RESPONSE` | `veto:model-response` | `ModelResponsePolicy` |
| `FRONTEND` | `veto:frontend` | `FrontendContribution` |
| `FILE_OBSERVATION` | `veto:file-observation` | `FileObservation` |
| `INPUT_PROTECTION` | `veto:input-protection` | `InputProtection` |
| `FILE_PROTECTION` | `veto:file-protection` | `FileProtection` |
| `SESSION_LIFECYCLE` | `veto:session-lifecycle` | `SessionLifecycle` |
| `DATA_LIFECYCLE` | `veto:data-lifecycle` | `DataLifecycle` |
| `TOOLS` | `veto:tools` | `Tool` |
| `NATIVE_TOOLS` | `veto:native-tools` | `CapabilityTool<?>` |
| `CATEGORIES` | `veto:tool-categories` | `ToolCategory` |
| `PROMPTS` | `veto:prompts` | `PromptContribution` |
| `OBSERVATION` | `veto:observation-middleware` | `ObservationMiddleware` |

Use `NATIVE_TOOLS` for record-authored in-process Java tools. Use `TOOLS` and
`ToolContribution` for schema-authored JSON tools. Registration validates coherence;
execution still passes through selection, admission, cancellation, and resource checks.

## Host services and PluginHost

`context.service(Class<T>)` returns an optional host object using exact class identity. It is
the entry point for typed Java boundaries when the host grants them. A plugin must degrade or
fail safely when an optional service is absent. A retained object cannot turn an expired call,
stopped plugin, or revoked resource grant into a valid operation.

`PluginHost.invocation(tool)` derives owner, session, agent, request, and call facts from a
live authorized invocation; plugin-supplied identity text grants nothing. `await` attaches
foreground waiting to that invocation. `wake` is only a scheduling hint. `publish` and
`invalidate` require an authorized selected session. `whenReady` runs after host migrations,
but its callback receives no automatic authorization for later effects.

## Named services between plugins

`context.services()` is a class-independent directory of name and exact major-version pairs.
Requests and responses are bounded `JsonValue` trees. `available()` exposes protocol name,
version, and host-attributed provider ID. Duplicate name/version pairs fail activation;
distinct major versions may coexist.

Lookup and invocation apply caller/provider lifecycle and current selection checks. A retained
handle pins a descriptor but bypasses no check. Expected public failures use
`ServiceException`; unexpected provider diagnostics are hidden by the host. Provider classes,
Java serialization, and arbitrary objects do not cross this boundary.

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
