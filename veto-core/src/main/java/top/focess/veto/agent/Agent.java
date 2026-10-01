package top.focess.veto.agent;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;

/**
 * The identity + API surface of a Veto agent. Holds the persona, tool whitelist, turn history, and
 * a volatile state machine. Owns its {@code AgentRunner} internally; workflows/transports interact
 * only through this API — they never touch the virtual thread, state machine, or loop mechanics.
 */
public interface Agent {

    // --- Identity ---
    /** The agent's stable identity (its persona id). */
    @NonNull String id();

    /** The persona's display name. */
    @NonNull String name();

    /** The persona currently in effect (identity, role and authorized tools). */
    @NonNull AgentPersona persona();

    /** The tool names this agent is authorized to call. */
    @NonNull Set<String> whitelistedTools();

    /** The agent's current lifecycle state. */
    @NonNull AgentState state();

    // --- Execution ---

    /** Submit a prompt for the agent to work on. Non-blocking — the virtual thread picks it up. */
    void submit(@NonNull String prompt);

    /**
     * Non-blocking submit with a callback attached to this request's result. The callback runs
     * inline when the result completes, potentially on a submitting or execution thread.
     */
    void submit(@NonNull String prompt, Consumer<AgentResult> callback);

    /** Block on the latest submitted request view (or until the timeout elapses). */
    @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException;

    /** Latest request's result view; use a retained handle to observe a specific submission. */
    @NonNull CompletableFuture<AgentResult> result();

    /**
     * Submits a prompt and returns the actual execution-owned request handle. Its identity, result
     * and settlement remain associated with this submission independently of later submissions.
     */
    @NonNull RequestHandle submitRequest(@NonNull String prompt);

    // --- Lifecycle ---
    /** Request termination; actual exit is confirmed separately via {@link #awaitTermination}. */
    void terminate(); // Request termination; completion is confirmed separately.

    /** Confirm actual execution exit. Implementations without confirmation return false. */
    default boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
        return false;
    }

    /** Cancel only the task identified by its result handle and confirm its execution exit. */
    default boolean cancelTask(
            @NonNull CompletableFuture<AgentResult> task, @NonNull Duration timeout)
            throws InterruptedException {
        return false;
    }

    // --- History ---
    /** The agent's durable turn history, oldest first. */
    @NonNull List<TurnRecord> history();

    /** The read-history (for drift detection). */
    @NonNull ReadHistory readHistory();

    /** Compact the agent's turn history segment. */
    void compact();
}
