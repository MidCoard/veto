package top.focess.veto.api.plugin.agent;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.agent.tool.NativeTool;

/** One ephemeral child owned by the currently authorized tool invocation. */
@NullMarked
public interface IsolatedAgent extends AgentHost.Child, AutoCloseable {
    record Limits(
            int calls,
            Duration timeout,
            int inputTokens,
            int outputTokens,
            int framingReserveBytes) {
        public Limits {
            if (calls < 1
                    || timeout.isNegative()
                    || timeout.isZero()
                    || inputTokens < 1
                    || outputTokens < 1
                    || framingReserveBytes < 0)
                throw new IllegalArgumentException("Invalid isolated execution limits");
        }
    }

    record Terminal(String tool, int reservedCalls, AgentProfile.Prompt prompt) {
        public Terminal {
            if (tool.isBlank() || reservedCalls < 1)
                throw new IllegalArgumentException("Invalid terminal contract");
        }
    }

    record Spec(
            String name,
            AgentProfile.Prompt description,
            AgentProfile.Prompt system,
            List<String> tiers,
            Limits limits,
            Terminal terminal) {
        public Spec {
            tiers = List.copyOf(tiers);
            if (tiers.isEmpty()) throw new IllegalArgumentException("No model tiers");
        }
    }

    record Usage(
            String id,
            String model,
            long elapsedMillis,
            int calls,
            long inputTokens,
            long outputTokens) {}

    interface Runtime {
        String id();

        void authorize(String operation);

        int observationBudgetBytes();

        Usage usage();

        void complete(String result);
    }

    interface Tools extends AutoCloseable {
        List<NativeTool<?>> tools();

        default void check() {}

        void close();
    }

    @FunctionalInterface
    interface Factory {
        Tools open(Runtime runtime);
    }

    Limits limits();

    Usage usage();

    boolean budgetExhausted();

    /**
     * Revokes new effects and waits for actual termination within the host close deadline. Throws
     * if the execution does not stop; that failure never implies settled. The host retains the
     * execution and tools for cleanup after real termination. Caller interruption is preserved.
     */
    @Override
    void close();
}
