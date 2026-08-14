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

/**
 * The identity + API surface of a Veto agent. Holds the persona, tool whitelist, turn history, and
 * a volatile state machine. Owns its {@code AgentRunner} internally; workflows/transports interact
 * only through this API — they never touch the virtual thread, state machine, or loop mechanics.
 */
public interface Agent {

    // --- Identity ---
    @NonNull String id();

    @NonNull String name();

    @NonNull AgentPersona persona();

    @NonNull Set<String> whitelistedTools();

    @NonNull AgentState state();

    // --- Execution ---

    /** Submit a prompt for the agent to work on. Non-blocking — the virtual thread picks it up. */
    void submit(@NonNull String prompt);

    /**
     * Non-blocking submit with a callback fired on the agent's virtual thread when the task done.
     */
    void submit(@NonNull String prompt, Consumer<AgentResult> callback);

    /** Block the caller until the current task completes (or the timeout elapses). */
    @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException;

    /** The current task's result future, or a completed future if idle. */
    @NonNull CompletableFuture<AgentResult> result();

    // --- Lifecycle ---
    void pause(); // → PAUSED

    void resume(); // → RUNNING

    void terminate(); // → TERMINATED, virtual thread stopped

    // --- History ---
    @NonNull List<TurnRecord> history();

    /** The read-history (for drift detection). */
    @NonNull ReadHistory readHistory();

    /** Compact the agent's turn history segment. */
    void compact();
}
