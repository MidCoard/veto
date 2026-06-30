package top.focess.veto.agent;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.mcp.ToolDefinition;

/**
 * The {@link Agent} implementation. Owns its {@link AgentRunner} internally on a virtual thread;
 * workflows/transports interact only through the {@link Agent} API. The runner blocks on its action
 * queue while {@link AgentState#IDLE}; {@link #submit} enqueues a {@link
 * AgentAction.UserPromptAction} and the virtual thread wakes.
 */
public class VetoAgent implements Agent {

    private final @NonNull String id;
    private final @NonNull AgentPersona persona;
    private final @NonNull Set<String> whitelistedTools;
    private final @NonNull AgentRunner runner;
    private final @NonNull Thread virtualThread;

    public VetoAgent(@NonNull AgentPersona persona, @NonNull AgentRunner runner) {
        this.id = persona.id() != null ? persona.id() : UUID.randomUUID().toString();
        this.persona = persona;
        this.whitelistedTools = runner.whitelistedToolsView();
        this.runner = runner;
        this.virtualThread = Thread.ofVirtual().name("agent-" + id).start(runner::run);
    }

    @Override
    public @NonNull String id() {
        return id;
    }

    @Override
    public @NonNull String name() {
        return persona.name();
    }

    @Override
    public @NonNull AgentPersona persona() {
        return persona;
    }

    @Override
    public @NonNull Set<String> whitelistedTools() {
        return whitelistedTools;
    }

    @Override
    public @NonNull AgentState state() {
        return runner.state();
    }

    @Override
    public void submit(@NonNull String prompt) {
        runner.startTask(null, new AgentAction.UserPromptAction(prompt));
    }

    @Override
    public void submit(@NonNull String prompt, @Nullable Consumer<AgentResult> callback) {
        runner.startTask(callback, new AgentAction.UserPromptAction(prompt));
    }

    @Override
    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        return runner.await(timeout);
    }

    @Override
    public @NonNull CompletableFuture<AgentResult> result() {
        return runner.result();
    }

    @Override
    public void pause() {
        runner.enqueue(new AgentAction.PauseAction());
    }

    @Override
    public void resume() {
        runner.enqueue(new AgentAction.ResumeAction());
    }

    @Override
    public void terminate() {
        runner.terminate();
        runner.enqueue(new AgentAction.TerminateAction());
    }

    @Override
    public @NonNull List<TurnRecord> history() {
        return runner.history();
    }

    @Override
    public @NonNull ReadHistory readHistory() {
        return runner.readHistory();
    }

    @Override
    public void compact() {
        runner.startTask(null, new AgentAction.CompactAction());
    }

    /**
     * Updates the model binding (provider/model/credential) — used when the user switches agent.
     */
    public void bind(AgentRunner.@NonNull LlmBinding binding) {
        runner.bind(binding);
    }

    /**
     * Subscribes a user-facing-message listener (the emission seam) for the duration a transport
     * cares about streaming. The listener fires on the agent's virtual thread as each {@code
     * response.message} is emitted.
     */
    public void addMessageListener(java.util.function.@NonNull Consumer<String> listener) {
        runner.addMessageListener(listener);
    }

    /** Unsubscribes a user-facing-message listener. */
    public void removeMessageListener(java.util.function.@NonNull Consumer<String> listener) {
        runner.removeMessageListener(listener);
    }

    /** The persona's resolved manifest (for the PromptCompiler / tests). */
    public @NonNull Set<ToolDefinition> manifest() {
        return persona.whitelistedTools();
    }
}
