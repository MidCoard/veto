# Veto plugin packaging API — experimental

For building and running Veto, start with the [project README](../README.md).
This module is the provisional Java plugin lifecycle binding. External Java-JAR activation is not available. For working script plugins, use
the separate [script runtime](../veto-plugin-runtime/README.md).

## Implemented behavior

`veto-plugin-api` owns package identity and initialization/start/close. It
re-exports [veto-extension](../veto-extension/README.md), which owns shared
contributions and initial application contracts.

`PluginContributions` is a bounded immutable list of typed
`ExtensionContribution<?>` values. `PluginContext` carries identity metadata only;
it does not issue permissions or approved invocation services.

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
