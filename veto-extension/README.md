# Veto extension layer

For building and running Veto, start with the [project README](../README.md).
This module provides the shared Java registration substrate for application modules
and experimental plugins. It is independent of Spring and credential protection.
The dependency direction is `veto-plugin-api -> veto-extension`.

## Implemented behavior

- `ExtensionPoint<T>` identifies a contract by namespaced ID, exact major version,
  Java class identity and cardinality.
- `ExtensionContribution<T>` supplies an implementation, local ID and within-point
  ordering constraints. The host supplies source identity and provenance.
- `ExtensionCatalog.Builder` validates and stages contributions atomically per source.
  It rejects duplicate identities, incompatible contracts, missing ordering targets,
  cycles and cross-point ordering constraints.
- `freeze()` validates required points and catalog constraints, then returns an
  immutable registration snapshot. Implementation objects may still have state.
- The catalog does not instantiate plugins, grant permissions, start handlers or
  publish itself into a running application.

Registration provenance is not authority. A category or contribution never grants
access to a host service.

## Initial contracts

| Point | Contribution |
|---|---|
| `veto:tools` | Computation-only tool description, schemas, categories and handler |
| `veto:tool-categories` | Display label and description |
| `veto:prompts` | Static package resource path |
| `veto:frontend` | Browser ESM activation, React registrations and scoped JSON actions |
| `veto:reference-renderers` | Browser reference mount, initial component tree and scoped action handler |
| `veto:observation-middleware` | Observation-text transform callback |

`Cancellation` carries a cancellation signal, not an approved invocation.
These contracts are experimental. The transform callback exists in the fixture;
production agent middleware still uses its existing pipeline.

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
tools through this catalog. Generic model hooks and bound plugin services are not
implemented.

## Browser references

Java plugins register `ReferenceRenderer` contributions through
`StandardExtensionPoints.REFERENCE_RENDERERS`. Each contribution declares its
reference token type, initial `PluginView`, and an action handler. The shared UI
protocol contains text, buttons, row/column groups, localized labels, and server
or local-reset actions. It has no credential-specific component kind.

For example, a build-status plugin can mount `[JOB_REF:build-42]`, show a
“Refresh status” button, and return a new view containing “Completed”. The same
interpreter renders secret-protection's masked text and Show button. Only that
plugin defines its masking, show action, Hide button, and transient-view policy.

The browser uses only the session's selected contributions. Button actions go to
`POST /api/sessions/{name}/plugin-references/actions` with the renderer ID, agent
ID, reference, and action ID. The host authorizes the session owner and invokes
the owning plugin through lifecycle admission. The handler receives an
authenticated owner/session/agent scope and returns a new view; it must validate
the reference and supported action against that scope. Missing references return
HTTP 410. Responses use `Cache-Control: no-store`; actions do not modify model
history and are not registered as tools.

Plugins may declare a view reset interval and reset-on-hidden behavior. RESET
buttons restore the initial view locally, without a server request. The host
supplies keyboard dismissal, pending/error states, stale-response cancellation,
localization, and bounded component trees. This is a declarative UI protocol,
not a loader for arbitrary browser JavaScript bundles.

The secret plugin's underlying captures still expire after 30 minutes and are
cleared on restart or logout. Its revealed view resets after 30 seconds or when
the page is hidden; these settings live in the plugin contribution.

## Verify

Run from the repository root:

```sh
./gradlew :veto-extension:test :veto-plugin-api:test
./gradlew :veto-extension:spotlessCheck :veto-plugin-api:spotlessCheck :veto-plugin-fixture:spotlessCheck
```

Tests cover atomic registration, ownership, contract compatibility, ordering,
required/singleton points, failed-source discard, frozen snapshots and the standalone
Java fixture. See the [plugin API README](../veto-plugin-api/README.md) for packaging.

## Executable browser plugins

`StandardExtensionPoints.FRONTEND` accepts `FrontendExtension(module, handler)`.
The module is self-contained browser ESM exporting `activate(host)`. Package it
as a plugin resource and read its source when registering. Java supplies loading,
registration, lifecycle admission, and authenticated actions; browser UI logic
lives in JavaScript, or TypeScript/JSX compiled to JavaScript.

The host provides its React instance, `registerReferenceRenderer(type, Component)`,
`registerPanel(id, "conversation.footer", Component)`, and a lifecycle abort
signal. `activate` optionally returns a disposer. Registrations are scoped to the
selected session/agent and UI surface. Components receive the reference where
applicable, locale, session, agent, and `context.invoke(action, arguments, signal)`.
They can use React hooks, custom elements and event handlers. They are not limited
to the declarative `PluginView` primitives, which remain an optional alternative.

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
