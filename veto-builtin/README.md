# Built-in tools plugin

`BuiltinPlugin` registers all 37 public tools through the same Java plugin API as
external plugins: workspace operations, process/background tasks, skills, questions,
memory, monitors, group collaboration, web search/fetch, authenticated repository
reading, planning and cited answers. The legacy create_node implementation also lives here; its existing visibility is unchanged. Four private reader tools
are created by the plugin for an individual child reader, never globally advertised.

The module compiles against `veto-api`, without core or Spring. It owns tool schemas,
validation, transformations, formatting, plan interpretation and document processing.
`HostCapabilityTool` receives authorized host services through public API contracts.
Core retains sandbox/egress/credential authority, ownership and session checks,
shared persistence/schedulers, agent lifecycle and model selection. Plugin tool code
cannot obtain raw credentials or bypass the normal gateway by invoking these ports.

`WebReadSession` owns source segmentation, search, evidence selection and terminal
result validation; core runs its private tools using the shared AgentRunner.
Monitor tools interpret dates and render results. Repository tools select response
fields; the host performs the fixed authorized HTTP operation and masks credentials.
All feature prompts are MDC resources compiled through the host PromptCompiler.

Core bundles the plugin at runtime and discovers it through ServiceLoader. Existing
public tool names are preserved by configured contribution aliases. Provenance,
lifecycle admission and session plugin selection remain active.

## Search providers

DuckDuckGo and Brave ship inside this plugin and are available automatically alongside
`web_search`. There is no separate search plugin to install or select. DuckDuckGo is
keyless and the default. Select Brave with `veto.websearch.provider: brave` and configure
`veto.websearch.brave.api-key`; the application binds that value to the builtin plugin's
`brave-api-key` configuration. BuiltinPlugin closes both providers with its lifecycle.

Other plugins implement `top.focess.veto.api.search.SearchProvider` and register a
`Contribution.of(StandardContributionPoints.SEARCH_PROVIDERS, "my-search", provider)`.
Use a unique provider `name()` and select it through `veto.websearch.provider`.
The host registry preserves session selection, authorization and plugin lifecycle checks.
