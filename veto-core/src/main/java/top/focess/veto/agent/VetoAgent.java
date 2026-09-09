package top.focess.veto.agent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.monitor.MonitorService;

/**
 * The {@link Agent} implementation. Owns its {@link AgentRunner} internally on a virtual thread;
 * workflows/transports interact only through the {@link Agent} API. The runner blocks on its action
 * queue while {@link AgentState#IDLE}; {@link #submit} enqueues a {@link
 * AgentAction.UserPromptAction} and the virtual thread wakes.
 */
public class VetoAgent implements Agent {

    private final @NonNull String id;
    private final @NonNull AgentRunner runner;
    private final boolean userInteractionEnabled;

    public VetoAgent(@NonNull AgentPersona persona, @NonNull AgentRunner runner) {
        this(persona, runner, true);
    }

    public VetoAgent(
            @NonNull AgentPersona persona,
            @NonNull AgentRunner runner,
            boolean userInteractionEnabled) {
        this.id = persona.id();
        this.runner = runner;
        this.userInteractionEnabled = userInteractionEnabled;
        Thread.ofVirtual().name("agent-" + id).start(runner::run);
    }

    public boolean hasPendingWork() {
        return runner.hasPendingWork();
    }

    public boolean userInteractionEnabled() {
        return userInteractionEnabled;
    }

    /** User commands are queued independently of a workflow's submit/await handoff. */
    public void submitUserPrompt(@NonNull String prompt) {
        if (!userInteractionEnabled) throw new IllegalStateException("Agent is read-only");
        if (state() == AgentState.TERMINATED)
            throw new IllegalStateException("Agent has terminated");
        runner.enqueue(new AgentAction.DirectUserPromptAction(prompt));
    }

    public void attachMonitor(@NonNull MonitorService service) {
        runner.attachMonitor(service);
    }

    public void signalMonitor() {
        runner.signalMonitor();
    }

    @Override
    public @NonNull String id() {
        return id;
    }

    @Override
    public @NonNull String name() {
        return runner.personaView().name();
    }

    @Override
    public @NonNull AgentPersona persona() {
        return runner.personaView();
    }

    @Override
    public @NonNull Set<String> whitelistedTools() {
        return runner.whitelistedToolsView();
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

    void onTermination(@NonNull Runnable callback) {
        runner.onTermination(callback);
    }

    public @NonNull UUID sessionId() {
        return runner.sessionId();
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
    public void setLocale(Locale locale) {
        runner.setLocale(locale);
    }

    /** The session's message locale (see {@link #setLocale}). */
    public @NonNull Locale locale() {
        return runner.locale();
    }

    /**
     * Seeds replayed history (from the durable turn log) into the runner on session activate.
     * Idempotent; see {@link AgentRunner#seedHistory}.
     */
    public void seedHistory(@NonNull List<TurnRecord> history) {
        runner.seedHistory(history);
    }

    /**
     * Subscribes a user-facing-message listener (the emission seam) for the duration a transport
     * cares about streaming. The listener fires on the agent's virtual thread as each {@code
     * response.message} is emitted.
     */
    public void addMessageListener(@NonNull Consumer<String> listener) {
        runner.addMessageListener(listener);
    }

    /** Unsubscribes a user-facing-message listener. */
    public void removeMessageListener(@NonNull Consumer<String> listener) {
        runner.removeMessageListener(listener);
    }

    /**
     * Subscribes an interim-thought listener (the thought emission seam) for the duration a
     * transport cares about streaming reasoning. The listener fires on the agent's virtual thread
     * as each {@code response.thought} is emitted, before the matching message.
     */
    public void addThoughtListener(@NonNull Consumer<String> listener) {
        runner.addThoughtListener(listener);
    }

    /** Unsubscribes an interim-thought listener. */
    public void removeThoughtListener(@NonNull Consumer<String> listener) {
        runner.removeThoughtListener(listener);
    }

    /**
     * Subscribes a HITL-veto listener (the veto emission seam) for the duration a transport cares
     * about rendering veto pickers. The listener fires on the agent's virtual thread when a tool
     * call parks for approval.
     */
    public void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runner.addVetoListener(listener);
    }

    /** Unsubscribes a HITL-veto listener. */
    public void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runner.removeVetoListener(listener);
    }

    /**
     * Subscribes a tool-call listener (the transparency emission seam) for the duration a transport
     * cares about streaming per-tool-call indicators. The listener fires on the agent's virtual
     * thread when a TOOL_CALL turn is appended — i.e. just before the model receives the matching
     * tool result.
     */
    public void addToolCallListener(@NonNull Consumer<AgentRunner.ToolCallEvent> listener) {
        runner.addToolCallListener(listener);
    }

    /** Unsubscribes a tool-call listener. */
    public void removeToolCallListener(@NonNull Consumer<AgentRunner.ToolCallEvent> listener) {
        runner.removeToolCallListener(listener);
    }

    /**
     * Subscribes a tool-result listener (the transparency emission seam) for the duration a
     * transport cares about streaming the observation the model received. The listener fires on the
     * agent's virtual thread when a TOOL_RESPONSE turn is appended.
     */
    public void addToolResultListener(@NonNull Consumer<AgentRunner.ToolResultEvent> listener) {
        runner.addToolResultListener(listener);
    }

    /** Unsubscribes a tool-result listener. */
    public void removeToolResultListener(@NonNull Consumer<AgentRunner.ToolResultEvent> listener) {
        runner.removeToolResultListener(listener);
    }

    /** The persona's resolved manifest (for the PromptCompiler / tests). */
    public @NonNull Set<ToolDefinition> manifest() {
        return runner.personaView().whitelistedTools();
    }
}
