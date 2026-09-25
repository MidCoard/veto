package top.focess.veto.api.plugin.agent;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.agent.tool.NativeTool;

/** One ephemeral child owned by the currently authorized tool invocation. */
@NullMarked
public interface IsolatedAgent extends AgentHost.Child, AutoCloseable {
    /**
     * Hard execution limits requested for an isolated child.
     *
     * @param calls maximum model calls
     * @param timeout maximum wall-clock execution time
     * @param inputTokens maximum input tokens
     * @param outputTokens maximum output tokens
     * @param framingReserveBytes bytes reserved for host protocol framing
     */
    record Limits(
            int calls,
            Duration timeout,
            int inputTokens,
            int outputTokens,
            int framingReserveBytes) {
        /** Validates that every execution limit is usable. */
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

    /**
     * Terminal tool contract used to return the child result.
     *
     * @param tool terminal tool name
     * @param reservedCalls calls reserved so the terminal action remains reachable
     * @param prompt prompt describing the terminal result contract
     */
    record Terminal(String tool, int reservedCalls, AgentProfile.Prompt prompt) {
        /** Validates the terminal tool name and reserved call count. */
        public Terminal {
            if (tool.isBlank() || reservedCalls < 1)
                throw new IllegalArgumentException("Invalid terminal contract");
        }
    }

    /**
     * Complete requested isolated-agent configuration.
     *
     * @param name display name
     * @param description structured purpose description
     * @param system structured system prompt
     * @param tiers acceptable model tiers in preference order; copied on construction
     * @param limits hard requested execution limits
     * @param terminal terminal result contract
     */
    record Spec(
            String name,
            AgentProfile.Prompt description,
            AgentProfile.Prompt system,
            List<String> tiers,
            Limits limits,
            Terminal terminal) {
        /** Defensively copies and validates the model-tier preference list. */
        public Spec {
            tiers = List.copyOf(tiers);
            if (tiers.isEmpty()) throw new IllegalArgumentException("No model tiers");
        }
    }

    /**
     * Immutable host-accounted execution usage snapshot.
     *
     * @param id execution ID
     * @param model selected model ID
     * @param elapsedMillis elapsed wall-clock milliseconds
     * @param calls consumed model calls
     * @param inputTokens consumed input tokens
     * @param outputTokens consumed output tokens
     */
    record Usage(
            String id,
            String model,
            long elapsedMillis,
            int calls,
            long inputTokens,
            long outputTokens) {}

    /** Invocation-scoped host bridge supplied while constructing child tools. */
    interface Runtime {
        /**
         * Returns the host identity of this isolated execution.
         *
         * @return the isolated execution ID
         */
        String id();

        /**
         * Requires host authorization for the named operation.
         *
         * @param operation operation whose authority is required
         */
        void authorize(String operation);

        /**
         * Returns the payload allowance for a single observation.
         *
         * @return the maximum observation payload size accepted by the host
         */
        int observationBudgetBytes();

        /**
         * Returns usage accounted by the host so far.
         *
         * @return the current host-accounted usage snapshot
         */
        Usage usage();

        /**
         * Completes the isolated execution with its terminal result.
         *
         * @param result terminal result payload
         */
        void complete(String result);
    }

    /** Invocation-scoped native tools and their lifecycle checks. */
    interface Tools extends AutoCloseable {
        /**
         * Returns the native tools available to the isolated child.
         *
         * @return the complete tool set exposed to the child
         */
        List<NativeTool<?>> tools();

        /** Performs an optional readiness check before execution starts. */
        default void check() {}

        /** Releases resources owned by this tool set. */
        void close();
    }

    /** Opens one tool set for the supplied isolated runtime. */
    @FunctionalInterface
    interface Factory {
        /**
         * Opens invocation-scoped tools.
         *
         * @param runtime host bridge for the isolated invocation
         * @return the opened tool set
         */
        Tools open(Runtime runtime);
    }

    /**
     * Returns the limits enforced for this execution.
     *
     * @return the effective execution limits
     */
    Limits limits();

    /**
     * Returns usage accounted by the host so far.
     *
     * @return the current host-accounted usage snapshot
     */
    Usage usage();

    /**
     * Reports whether the execution has consumed any enforced budget.
     *
     * @return whether any effective execution budget has been exhausted
     */
    boolean budgetExhausted();

    /**
     * Revokes new effects and waits for actual termination within the host close deadline. Throws
     * if the execution does not stop; that failure never implies settled. The host retains the
     * execution and tools for cleanup after real termination. Caller interruption is preserved.
     */
    @Override
    void close();
}
