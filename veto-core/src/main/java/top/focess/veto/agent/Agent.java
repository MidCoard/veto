package top.focess.veto.agent;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;

/**
 * The identity + API surface of a Veto agent (LLD {@code hybrid_loop_design.md} §1.3, {@code
 * agent_class_design.md}). Holds the persona, tool whitelist, turn history, and a volatile state
 * machine. Owns its {@code AgentRunner} internally; workflows/transports interact only through this
 * API — they never touch the virtual thread, state machine, or loop mechanics.
 */
public interface Agent {

    // --- Identity ---
    String id();

    String name();

    AgentPersona persona();

    Set<String> whitelistedTools();

    AgentState state();

    // --- Execution ---

    /** Submit a prompt for the agent to work on. Non-blocking — the virtual thread picks it up. */
    void submit(String prompt);

    /**
     * Non-blocking submit with a callback fired on the agent's virtual thread when the task done.
     */
    void submit(String prompt, Consumer<AgentResult> callback);

    /** Block the caller until the current task completes (or the timeout elapses). */
    AgentResult await(Duration timeout) throws TimeoutException, InterruptedException;

    /** The current task's result future, or a completed future if idle. */
    CompletableFuture<AgentResult> result();

    // --- Lifecycle ---
    void pause(); // → PAUSED

    void resume(); // → RUNNING

    void terminate(); // → TERMINATED, virtual thread stopped

    // --- History ---
    List<TurnRecord> history();

    /** The read-history (for drift detection). */
    ReadHistory readHistory();
}
