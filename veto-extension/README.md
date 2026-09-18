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

## Verify

Run from the repository root:

```sh
./gradlew :veto-extension:test :veto-plugin-api:test
./gradlew :veto-extension:spotlessCheck :veto-plugin-api:spotlessCheck :veto-plugin-fixture:spotlessCheck
```

Tests cover atomic registration, ownership, contract compatibility, ordering,
required/singleton points, failed-source discard, frozen snapshots and the standalone
Java fixture. See the [plugin API README](../veto-plugin-api/README.md) for packaging.
