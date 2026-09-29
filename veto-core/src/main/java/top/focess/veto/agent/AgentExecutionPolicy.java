package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Immutable terminal policy and host effect guard, independent of feature tool names. */
public record AgentExecutionPolicy(IsolatedAgent.Terminal terminal, @NonNull Runnable check) {
    /** The default policy: no terminal tool and a no-op host effect guard. */
    public static @NonNull AgentExecutionPolicy ordinary() {
        return new AgentExecutionPolicy(null, () -> {});
    }
}
