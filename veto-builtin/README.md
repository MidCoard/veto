# Built-in tools plugin

`BuiltinPlugin` registers all 37 public tools through the same Java plugin API as
external plugins: workspace operations, process/background tasks, skills, questions,
memory, monitors, group collaboration, web search/fetch, authenticated repository
reading, planning and cited answers. The legacy create_node implementation also lives here; its existing visibility is unchanged. Four private reader tools
are created by the plugin for an individual child reader, never globally advertised.

The module compiles against `veto-api` without a dependency on core. Its persistent
feature stores use Spring Data JPA. It owns tool schemas,
validation, transformations, formatting, plan interpretation and document processing.
Workspace and network tools receive authorized host capabilities through public API
contracts; group, memory, process, monitor and question runtimes belong to builtin.
Core retains sandbox/egress/credential authority, ownership/session checks, generic agent execution and model selection. These API ports do not expose raw credentials. In-process Java remains trusted execution; these ports are not a confinement boundary.

`WebReadSession` owns source segmentation, search, evidence selection and terminal
result validation; core runs its private tools using the shared AgentRunner.
Monitor tools interpret dates and render results. Repository tools select response
fields and perform the fixed bounded HTTP operation through an invocation-bound
credential lease; the host authorizes the lease and masks observations.
All feature prompts are MDC resources compiled through the host PromptCompiler.

`veto-core` never bundles this plugin. Build its optional package with
`localPluginPackages` and install its directory under `plugins/`. The plugin
requests its established public tool names; operator aliases can override them. Provenance,
lifecycle admission and session plugin selection remain active.

## Search providers

DuckDuckGo and Brave ship inside this plugin and are available automatically alongside
`web_search`. There is no separate search plugin to install or select. DuckDuckGo is
keyless and the default. Set `search-provider: brave` and `brave-api-key` under
`veto.plugins.configuration[top.focess.builtin]` to use Brave. Retired
`veto.websearch.*` aliases are not interpreted. BuiltinPlugin closes both providers
with its lifecycle.

Builtin publishes one `veto.search` version 1 service. Its `providers` operation lists
available providers, and `search` takes a provider name with the query and filters.
Another plugin can register a provider by discovering this service through the generic
`PluginServices` directory, registering a revocable JSON callback there, then invoking
the service's `register` operation with its provider name and opaque callback ID.
Service-directory change events let a provider retry registration after startup or
directory publication. The host checks session selection, authorization and plugin lifecycle
on service and callback calls; disabling a provider revokes its callback.

Group lifecycle tools, including create_group, are grouped in `group/GroupTools`.
Delegation uses builtin group runtimes with admitted generic agent host operations.

The service, local providers and public tool share the same result policy: allow/block
filters use locale-independent, case-insensitive host suffix matching with `www.`
normalization; blocked matches win. Filtering precedes the requested result cap,
and nonpositive caps retain the default ten. The service enforces this policy on
external callbacks as well as bundled providers.

## Execution ownership

Builtin failures prefer canonical native `ToolErrorCode` enums. New results use
`HTTP_ERROR` for GitHub HTTP failures, `FETCH_FAILED` for authenticated fetch
failures, `TIMEOUT` for reader timeouts, `ENCODING_FAILED` for reader encoding,
`FILE_TOO_LARGE` for oversized memory content and `MEMORY_NOT_FOUND` for missing
memory items. Distinct reader/group failures retain native codes. The API rejects
duplicate native keys across groups; `Named` remains for extensions and tolerant
reading of historical keys, including the previous feature-specific names.

`response/AnswerWithCitationsTool` supports ordinary answers and generated plan answers.
`planning/PlanProgram` owns plan stepping and its execution loop; API runtime callbacks
supply authorized model/tool operations. Set `plan-max-steps` under `veto.plugins.configuration[top.focess.builtin]`.
Plan terminology replaces the former guided names in active code and observations.

Monitor operations are internal to builtin. No `MonitorCapability` or monitor tool interface is required by veto-api; any third-party plugin can register its own tools and generic `AgentInbox`. The current Java host and frontend ESM execution are trusted, not resource-isolated.

Static tool bounds live on argument record annotations and are enforced by the host
before dispatch, including private reader tools. Old paired documentation examples
retain their original feature diagnostic labels; current static-bound failures use
`INVALID_ARGUMENTS` with the host's field-specific diagnostic. Live evidence and
authorization checks remain in the owning runtime.

Compatibility limits retained in this repair: skill body parsing removes Markdown
`---` separators to preserve existing hash anchors; changing it requires a deliberate
anchor migration. `find_files` uses its documented portable glob subset while grep
includes use the platform glob matcher. Explicit-port `NO_PROXY` entries match an
explicit URI port; omitted default ports are not inferred. Plaintext grep source
rows cannot distinguish a filename containing the same numeric delimiter as a row.
