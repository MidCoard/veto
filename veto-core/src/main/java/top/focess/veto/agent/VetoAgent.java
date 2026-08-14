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
        this.id = persona.id();
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
    public void submit(@NonNull String prompt, Consumer<AgentResult> callback) {
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
     * Stamps the session's message locale (the request's Accept-Language on the REST path; null
     * resets to English) so agent-thread messages render in the user's language.
     */
    public void setLocale(java.util.Locale locale) {
        runner.setLocale(locale);
    }

    /** The session's message locale (see {@link #setLocale}). */
    public java.util.@NonNull Locale locale() {
        return runner.locale();
    }

    /**
     * Seeds replayed history (from the durable turn log) into the runner on session activate.
     * Idempotent; see {@link AgentRunner#seedHistory}.
     */
    public void seedHistory(java.util.@NonNull List<TurnRecord> history) {
        runner.seedHistory(history);
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

    /**
     * Subscribes an interim-thought listener (the thought emission seam) for the duration a
     * transport cares about streaming reasoning. The listener fires on the agent's virtual thread
     * as each {@code response.thought} is emitted, before the matching message.
     */
    public void addThoughtListener(java.util.function.@NonNull Consumer<String> listener) {
        runner.addThoughtListener(listener);
    }

    /** Unsubscribes an interim-thought listener. */
    public void removeThoughtListener(java.util.function.@NonNull Consumer<String> listener) {
        runner.removeThoughtListener(listener);
    }

    /**
     * Subscribes a HITL-veto listener (the veto emission seam) for the duration a transport cares
     * about rendering veto pickers. The listener fires on the agent's virtual thread when a tool
     * call parks for approval.
     */
    public void addVetoListener(
            java.util.function.@NonNull Consumer<top.focess.veto.agent.intercept.VetoPrompt>
                    listener) {
        runner.addVetoListener(listener);
    }

    /** Unsubscribes a HITL-veto listener. */
    public void removeVetoListener(
            java.util.function.@NonNull Consumer<top.focess.veto.agent.intercept.VetoPrompt>
                    listener) {
        runner.removeVetoListener(listener);
    }

    /**
     * Subscribes a tool-call listener (the transparency emission seam) for the duration a transport
     * cares about streaming per-tool-call indicators. The listener fires on the agent's virtual
     * thread when a TOOL_CALL turn is appended — i.e. just before the model receives the matching
     * tool result.
     */
    public void addToolCallListener(
            java.util.function.@NonNull Consumer<AgentRunner.ToolCallEvent> listener) {
        runner.addToolCallListener(listener);
    }

    /** Unsubscribes a tool-call listener. */
    public void removeToolCallListener(
            java.util.function.@NonNull Consumer<AgentRunner.ToolCallEvent> listener) {
        runner.removeToolCallListener(listener);
    }

    /**
     * Subscribes a tool-result listener (the transparency emission seam) for the duration a
     * transport cares about streaming the observation the model received. The listener fires on the
     * agent's virtual thread when a TOOL_RESPONSE turn is appended.
     */
    public void addToolResultListener(
            java.util.function.@NonNull Consumer<AgentRunner.ToolResultEvent> listener) {
        runner.addToolResultListener(listener);
    }

    /** Unsubscribes a tool-result listener. */
    public void removeToolResultListener(
            java.util.function.@NonNull Consumer<AgentRunner.ToolResultEvent> listener) {
        runner.removeToolResultListener(listener);
    }

    /** The persona's resolved manifest (for the PromptCompiler / tests). */
    public @NonNull Set<ToolDefinition> manifest() {
        return persona.whitelistedTools();
    }
}
