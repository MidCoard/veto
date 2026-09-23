# LLM provider plugin

ServiceLoader plugin `top.focess.llm-providers` contributes OpenAI, Anthropic,
Gemini and DeepSeek adapters through `veto:llm-providers`. This module depends on
`veto-api` and vendor SDKs, with no core or Spring dependency. It owns SDK creation,
request mapping, response decoding and SDK pool cleanup.

Provider contracts live in `top.focess.veto.api.llm`. The host supplies
`PromptRenderer`, backed by MDC and `PromptCompiler`; adapters cannot silently use
an inline prompt fallback. Core owns provider selection, credential/egress policy,
audit and retries. Provider availability is installation-wide, as model tier
resolution also serves requests outside agent sessions; agent tool selection stays
session-scoped.

Model tiers/profiles, local-model execution and the gateway remain in veto-core.
The existing ProviderType values are preserved; introducing arbitrary persisted
provider identifiers is a separate compatibility change.
