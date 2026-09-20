# Veto lifecycle fixture — development only

This package tests the experimental Java plugin lifecycle and shared extension
contracts. It is not a production plugin installer, and copying it into a Veto
directory does not activate it.

It contributes a text-length computation tool, a text category, a static prompt
and an observation-text transformer. It requests no host services. Its test
classloader checks API sharing; classloader separation is not a security sandbox.

From a Veto source checkout, run:

```sh
./gradlew :veto-plugin-fixture:pluginPackage :veto-plugin-api:test
```

The package contains `plugin.jar`, an experimental manifest, a configuration schema
and prompt resources. Configuration-driven script plugins and cross-harness
adapters remain proposed; this Java fixture does not demonstrate those features.
