package top.focess.veto.agent;

import java.util.function.LongFunction;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ChatMessage;

/** Immutable terminal policy and host effect guard, independent of feature tool names. */
public record AgentExecutionPolicy(Terminal terminal, @NonNull Runnable check) {
    /**
     * A terminal tool and its owner-bound prompt renderer.
     *
     * @param tool terminal tool name
     * @param reservedCalls calls reserved for completion
     * @param promptSource entry source ID used to replace earlier completion guidance
     * @param prompt renders completion guidance for the remaining call count
     */
    public record Terminal(
            @NonNull String tool,
            int reservedCalls,
            @NonNull String promptSource,
            @NonNull LongFunction<@NonNull ChatMessage> prompt) {}

    /** The default policy: no terminal tool and a no-op host effect guard. */
    public static @NonNull AgentExecutionPolicy ordinary() {
        return new AgentExecutionPolicy(null, () -> {});
    }
}
