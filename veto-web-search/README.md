# Bundled web-search plugin

`top.focess.web-search` supplies DuckDuckGo and Brave through
`veto:search-providers`. Its only Veto dependency is `veto-api`; it uses the same
ServiceLoader discovery, initialization, registration and shutdown as other Java
plugins. HTTP clients belong to the plugin and close with it.

Core selects the backend with `veto.websearch.provider` (default `duckduckgo`).
Brave's key is passed through the plugin's initialization configuration:

```yaml
veto:
  plugins:
    configuration:
      "[top.focess.web-search]":
        brave-api-key: ${BRAVE_API_KEY:}
```

The bundled application configuration also preserves `veto.websearch.brave.api-key`.
Each plugin receives only its own configuration map. Configuration is operator-owned
and is never added to the model's tool catalog.

The existing `web_search` tool name, arguments, result filtering and authorization
remain in core for this slice. Calls require both an execution permit and selection
of this plugin in the session's pinned bindings. New sessions select installed
plugins by default. Existing pinned sessions do not silently acquire this plugin;
start a new session with it selected. Removing its JAR leaves search unavailable
without preventing the host from starting.

Tests use local HTTP fixtures for request/response behavior and load this plugin
without `veto-core` on the classpath. Live search-service availability is not tested.
