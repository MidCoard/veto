package top.focess.veto.agent;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;

/**
 * The identity + API surface of a Veto agent. Holds the persona, tool whitelist, turn history, and
 * safely published execution status. One dedicated virtual thread owns execution and consumes
 * queued commands; callers may concurrently submit, query, cancel or stop. Cancellation/stop
 * signals can interrupt blocked execution. Request results and execution settlement are distinct.
 * Workflows/transports interact through this API rather than the execution thread.
 */
public interface Agent {

    // --- Identity ---
    /** The agent's stable identity (its persona id). */
    @NonNull String id();

    /** The persona's display name. */
    @NonNull String name();

    /** The identity fixed at construction, independent of plugin execution profiles. */
    @NonNull AgentPersona persona();

    /** The tool names this agent is authorized to call. */
    @NonNull Set<String> whitelistedTools();

    /** The agent's current lifecycle state. */
    @NonNull AgentState state();

    // --- Execution ---

    /** Submits a prompt with its own result and execution settlement. */
    default @NonNull RequestHandle submitRequest(@NonNull String prompt) {
        return submitRequest(prompt, null);
    }

    /**
     * Submits a prompt and attaches a callback to its result. The callback runs inline on the
     * submitting or completing thread.
     */
    @NonNull RequestHandle submitRequest(@NonNull String prompt, Consumer<AgentResult> callback);

    // --- Lifecycle ---
    /** Request termination; actual exit is confirmed separately via {@link #awaitTermination}. */
    void terminate(); // Request termination; completion is confirmed separately.

    /** Confirm actual execution exit. Implementations without confirmation return false. */
    default boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
        return false;
    }

    /** Cancel only the task identified by its request handle and confirm its execution exit. */
    default boolean cancelTask(@NonNull RequestHandle task, @NonNull Duration timeout)
            throws InterruptedException {
        return false;
    }

    // --- History ---
    /** The agent's durable turn history, oldest first. */
    @NonNull List<TurnRecord> history();

    /** The read-history (for drift detection). */
    @NonNull ReadHistory readHistory();

    /** Compact the agent's turn history segment. */
    @NonNull RequestHandle compact();
}
