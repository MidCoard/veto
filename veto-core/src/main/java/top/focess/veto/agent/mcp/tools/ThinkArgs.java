package top.focess.veto.agent.mcp.tools;

import top.focess.veto.agent.mcp.ToolDoc;

/**
 * Parameter container for the {@code think} agent tool. {@code think} is an {@link
 * top.focess.veto.agent.mcp.AgentToolDefinition} (engine-provided, always-on, frictionless - the
 * Gateway returns {@code NotScreened} for agent tools). It is a no-op the agent calls to continue
 * its thought flow for another step when it has no concrete tool to invoke but is not ready to
 * conclude; the loop routes termination on call presence, so emitting {@code think} (a call) keeps
 * the episode alive. Carries no arguments.
 */
@ToolDoc(
        description =
                """
                #### When to use
                Use `think` to continue your reasoning for another step when you have no concrete tool to invoke \
                but are not yet ready to conclude the turn - e.g. you have gathered partial information and want \
                a step to consolidate it before acting, or you are mid-plan and want to lay out the next \
                sub-goal before reaching for a tool.

                It is a frictionless, always-available no-op: the Gateway returns `NotScreened` for agent tools, \
                so it never blocks. Emitting `think` (a call) keeps the episode alive for one more step, because \
                the loop keys termination off whether you emitted any call.

                #### When NOT to use
                - Do not use `think` as a substitute for a real tool call when a tool would make progress - if you \
                can read, search, or edit, do that instead.
                - Do not use it to stall indefinitely; each `think` is a full round-trip. If you have enough to \
                answer, produce your final message instead.
                - Do not use it expecting a side effect; it changes nothing in the workspace or session state.

                #### Behavior
                A pure no-op. It takes no arguments and performs no action. Its only effect is procedural: by \
                occupying a `calls[]` slot it signals "keep going" to the agent loop, buying another reasoning \
                step. The observation returned is an acknowledgement.

                #### Return format
                An acknowledgement observation (no data). The value is the extra reasoning step, not the returned \
                text.

                #### Errors & edge cases
                - `think` cannot fail at the tool level (it does nothing). The only "error" is wasting a turn - \
                use it sparingly.
                - It carries no args; pass `{}`.

                #### Security
                `think` is an `AgentToolDefinition` (engine-provided, always-on). The Gateway returns \
                `NotScreened` - no path, no content, no semantic screening. It touches nothing on the host. Safe \
                to call any time.
                """,
        examples = {"{}"})
public record ThinkArgs() {}
