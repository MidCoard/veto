# LLM provider plugin

Installable plugin `top.focess.llm-providers` contributes OpenAI, Anthropic,
Gemini, DeepSeek and LOCAL adapters through `veto:llm-providers`. This module depends on
`veto-api` and vendor SDKs, with no core or Spring dependency. It owns SDK creation,
request mapping, response decoding and SDK pool cleanup.

Build its optional `plugin.json` package with `localPluginPackages` and install
the directory under the backend's `plugins/` folder.

Provider contracts live in `top.focess.veto.api.llm`. The host supplies
`PromptRenderer`, backed by MDC and `PromptCompiler`; adapters cannot silently use
an inline prompt fallback. Core owns provider selection, credential/egress policy,
audit and retries. Host operations can resolve installed providers outside an agent
session. Agent sessions must explicitly select `top.focess.llm-providers` and use
their pinned provider revision, alongside any desired tool plugins. A configured
model tier alone does not make its provider available in a session.

LOCAL takes a GGUF file path in the binding's `baseUrl` and an empty credential
reference. `LocalLlmProvider` resolves that artifact through the public
`LocalModelRuntime` host service, then uses ordinary chat messages and native tools.
The host shares model processes by canonical artifact path and context size with
core intent inference. Missing artifacts fail the call; no other model is substituted.
Network providers retain the host credential/egress resolver. The uniform caller
selects, resolves and executes providers without a LOCAL branch.
