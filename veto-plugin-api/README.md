# Veto plugin packaging API — experimental

For building and running Veto, start with the [project README](../README.md).
This module is the provisional Java plugin lifecycle binding. External Java-JAR activation is not available. For working script plugins, use
the separate [script runtime](../veto-plugin-runtime/README.md).

## Implemented behavior

`veto-plugin-api` owns package identity and initialization/start/close. It
re-exports [veto-extension](../veto-extension/README.md), which owns shared
contributions and initial application contracts.

`PluginContributions` is a bounded immutable list of typed
`ExtensionContribution<?>` values. `PluginContext` carries identity metadata, a live
read-only lifecycle state via `state()`, and a failure-reporting callback; it does not issue permissions or approved invocation services.

`AbstractVetoPlugin` provides lifecycle callbacks without allocating threads or owning
lifecycle state. Veto's `PluginManager` owns one shared control executor and each
plugin's `ManagedPlugin` handle. Handles serialize lifecycle transitions and invocation
admission; tool handlers run on caller threads. Closing a handle drains admitted calls
and releases the plugin's resources once. The manager shuts down the shared executor
after closing all handles.

The standalone fixture contributes a computation tool, a category, a static prompt
and an observation-text transformer. Its JAR does not bundle shared contracts.
Package tests load it with shared API class identities and without application
libraries. Classloader separation is not a security sandbox.

## Build and verify

Run from the repository root:

```sh
./gradlew :veto-extension:test :veto-plugin-api:test
./gradlew :veto-plugin-fixture:pluginPackage
```

The fixture is generated at
`veto-plugin-fixture/build/plugin/org.veto.fixture/0.1.0/`, with its own README.
Building or copying this package does not activate it in Veto.

## Limitations

The manifest and Java SPI are experimental. Java package activation, portable model hooks and cross-client adapters are not
implemented by this module. The separate script runtime supports a different
manifest and validates its own descriptors and messages. The fixture demonstrates lifecycle and registration mechanics,
not production protection or plugin installation.
