# Secret protection plugin

The plugin processes user input and tool results before they enter the primary
Agent context. It creates a child Agent with the host-managed local provider,
its own prompt and the ordinary `import_credential` tool. Other Agent contexts
are not intercepted by this plugin.

Credentials are imported into the owner's encrypted vault and replaced with
references. Private credentials remain opaque to the primary model. Making a
credential model-readable requires approval; `read_credential` checks its owner,
scope and visibility before returning the value. Successful reads carry a
result-scoped option to skip Secret processing. Changing the result content
revokes that option.

The plugin uses the existing Agent, tool execution, HITL and cancellation paths.
Missing local inference or incomplete processing cancels publication instead of
passing the original text to the primary model.

Operators can set `veto.plugins.configuration[top.focess.secret-protection].modelPath`
to an explicit local GGUF path. The default is `./models/veto-secret-agent.gguf`.
This selects the artifact only; the native tool-calling contract and private
credential handling remain unchanged. Validate the chosen model before deployment.

Runtime integration passes its automated checks. The earlier extraction-only model
is not a validated model for this tool-calling workflow. Release assembly requires the new
model artifact and its verified checksum; model files remain outside Git.
