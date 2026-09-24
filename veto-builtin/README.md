# Built-in tools plugin

`BuiltinPlugin` registers all 37 public tools through the same Java plugin API as
external plugins: workspace operations, process/background tasks, skills, questions,
memory, monitors, group collaboration, web search/fetch, authenticated repository
reading, planning and cited answers. The legacy create_node implementation also lives here; its existing visibility is unchanged. Four private reader tools
are created by the plugin for an individual child reader, never globally advertised.

The module compiles against `veto-api`, without core or Spring. It owns tool schemas,
validation, transformations, formatting, plan interpretation and document processing.
`HostCapabilityTool` receives authorized host services through public API contracts.
Core retains sandbox/egress/credential authority, ownership/session checks, generic agent execution and model selection. Monitor schema, restoration, scheduling, delivery and frontend now belong to this plugin. Group/memory/process lifecycle extraction is still pending. These API ports do not expose raw credentials. In-process Java remains trusted execution; these ports are not a confinement boundary.

`WebReadSession` owns source segmentation, search, evidence selection and terminal
result validation; core runs its private tools using the shared AgentRunner.
Monitor tools interpret dates and render results. Repository tools select response
fields; the host performs the fixed authorized HTTP operation and masks credentials.
All feature prompts are MDC resources compiled through the host PromptCompiler.

`veto-app` bundles the plugin at runtime; `veto-core` does not and discovers it through ServiceLoader. Existing
public tool names are preserved by configured contribution aliases. Provenance,
lifecycle admission and session plugin selection remain active.

## Search providers

DuckDuckGo and Brave ship inside this plugin and are available automatically alongside
`web_search`. There is no separate search plugin to install or select. DuckDuckGo is
keyless and the default. Select Brave with `veto.websearch.provider: brave` and configure
`veto.websearch.brave.api-key`; the application binds that value to the builtin plugin's
`brave-api-key` configuration. BuiltinPlugin closes both providers with its lifecycle.

Other plugins register a named JSON service through `StandardContributionPoints.SERVICES`.
The search contract is `veto.search:<name>` version 1; select `<name>` through
`veto.websearch.provider`. See the generic service API and JSON protocol in veto-api/README.md.
The host registry preserves session selection, authorization and plugin lifecycle checks.

Group lifecycle tools, including create_group, are grouped in `group/GroupTools`.
Delegation uses the same `HostCapabilityTool` dispatch as other host-backed agent tools.

## Execution ownership

`response/AnswerWithCitationsTool` supports ordinary answers and generated plan answers.
`planning/PlanProgram` owns plan stepping and its execution loop; API runtime callbacks
supply authorized model/tool operations. The current configuration is `veto.plan.max-steps`.
Plan terminology replaces the former guided names in active code and observations.

Monitor operations are internal to builtin. No `MonitorCapability` or monitor tool interface is required by veto-api; any third-party plugin can register its own tools and generic `AgentWorkSource`. The current Java host and frontend ESM execution are trusted, not resource-isolated.
