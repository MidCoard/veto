package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.event.EventManager;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/**
 * Owns agent execution state, request transitions and the single-thread action queue.
 *
 * <p>The VetoAgent virtual thread owns ordinary execution state, wait registration, continuation
 * persistence and retirement. Thread.start publishes setup dependencies; the existing queue
 * publishes immutable runtime commands. External callers publish cancellation/stop intent under the
 * admission monitor, which also prevents an old cancellation interrupt reaching a new request. A
 * queued request proven not admitted may be cancelled and completed on its caller. Stop may
 * immediately reject an active result on the stopping caller without claiming settlement. Ordinary
 * results, wait release and termination callbacks run on the execution thread; late termination
 * registration invokes its callback on the registering caller. Result and execution-event callbacks
 * run outside the admission monitor. Live control/configuration values are published for concurrent
 * queries.
 */
public final class AgentRunner {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentRunner");
    private final @NonNull String agentId;
    private final @NonNull UUID sessionId;
    private final @NonNull ToolEngine toolEngine;
    final @NonNull ToolExecutionBoundary toolBoundary;
    private final @NonNull AgentPersona persona;
    // The execution thread applies queued inputs; volatile publishes them to external queries.
    private volatile @NonNull LlmBinding baseBinding;
    private volatile @NonNull Locale locale = Locale.ENGLISH;
    private @NonNull ToolResultPresentationMode toolResultPresentation =
            ToolResultPresentationMode.BASIC;
    private @NonNull AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.ordinary();
    private SessionPlugins sessionPlugins;
    private ModelTierRegistry modelTierRegistry;
    private long configurationRevision;
    private String configurationTransition;

    static final class BreakerTripException extends RuntimeException {}

    static final class VetoRefusedException extends RuntimeException {
        final boolean approvalRequested;

        VetoRefusedException() {
            this(false);
        }

        VetoRefusedException(boolean approvalRequested) {
            this.approvalRequested = approvalRequested;
        }
    }

    private volatile @NonNull ExecutionControl control = new ExecutionControl.Idle();
    private final @NonNull BlockingQueue<RunnerCommand> actionQueue;
    private final @NonNull List<QueuedRequest> deferredUserPrompts = new ArrayList<>();
    private final @NonNull AtomicBoolean workQueued = new AtomicBoolean();
    // Assigned once before Thread.start; never changed by execution or retirement.
    private Thread executionThread;
    // Stop intent is published by external callers; only the execution thread closes control.
    private volatile ExecutionControl.CloseReason stopReason;
    // Admission defaults are protected by this monitor; execution uses queued immutable inputs.
    private RunnerCommand.@NonNull Inputs submissionInputs;
    private Runnable terminationCallback;
    private boolean terminationNotified;
    EventManager eventManager;
    final @NonNull AgentContinuationExecution continuations;
    private final @NonNull AgentToolExecution tools;
    final @NonNull ModelSession models;
    final @NonNull AgentOutput output;
    private final String owner;

    /**
     * Creates a runner bound to an explicit session owner and session id. Does not start the loop;
     * {@link VetoAgent} attaches and starts its dedicated virtual thread to drain the action queue.
     */
    // WHY: these collaborator constructors only store callbacks/owner references; none invoke or
    // publish this runner. The final tool executor is assembled last, before any execution.
    @SuppressWarnings({"method.invocation", "methodref.receiver.bound", "argument", "assignment"})
    public AgentRunner(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine toolEngine,
            @NonNull ToolExecutionBoundary toolBoundary,
            List<LoopInterceptor> interceptors,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper objectMapper,
            long maxCallsPerEpisode,
            @NonNull LlmBinding binding,
            @NonNull AgentEventSink eventSink,
            @NonNull UUID userId,
            TurnLogService turnLogService,
            String owner,
            @NonNull UUID sessionId) {
        var responses = new ModelResponseValidation(toolEngine, objectMapper);
        List<LoopInterceptor> configuredInterceptors =
                interceptors == null ? List.of() : List.copyOf(interceptors);
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.owner = owner;
        this.toolEngine = toolEngine;
        this.toolBoundary = toolBoundary;
        this.persona = persona;
        this.baseBinding = binding;
        this.submissionInputs = new RunnerCommand.Inputs(binding, Locale.ENGLISH);
        actionQueue = new LinkedBlockingQueue<>();
        output =
                new AgentOutput(
                        new AgentHistory(turnLogService, sessionId, userId, agentId),
                        new AgentEvents(agentId, objectMapper, eventSink, sessionId),
                        promptCompiler,
                        new ToolResultPresenter(objectMapper),
                        toolEngine,
                        this::outputView);
        var hooks =
                new AgentPluginHooks(
                        owner,
                        sessionId.toString(),
                        agentId,
                        objectMapper,
                        caller,
                        () -> sessionPlugins,
                        () -> eventManager,
                        () -> control().open(),
                        () -> {
                            var request = control().request();
                            return request != null && request.cancelled;
                        });
        models =
                new ModelSession(
                        agentId,
                        promptCompiler,
                        responses,
                        toolEngine,
                        output,
                        hooks,
                        this,
                        objectMapper);
        continuations =
                new AgentContinuationExecution(
                        agentId,
                        sessionId,
                        owner,
                        maxCallsPerEpisode,
                        output,
                        actionQueue,
                        () -> sessionPlugins);
        tools =
                new AgentToolExecution(
                        toolEngine,
                        toolBoundary,
                        responses,
                        objectMapper,
                        configuredInterceptors,
                        output,
                        hooks,
                        this,
                        agentId,
                        userId,
                        owner,
                        sessionId);
    }

    /** Attaches the vault that gates autonomous plugin work on the owner's unlocked credentials. */
    public void attachExecutionVault(@NonNull KeysteadVault vault) {
        requireSetup();
        continuations.attachExecutionVault(vault);
    }

    /** The reason execution is parked (approval, question, breaker, etc.), or {@code null}. */
    public String executionWaitReason() {
        for (Wait reason : List.of(Wait.APPROVAL, Wait.QUESTION, Wait.BREAKER))
            if (control.waiting(reason)) return reason.name();
        return null;
    }

    void configureModelTiers(ModelTierRegistry registry) {
        requireSetup();
        modelTierRegistry = registry;
    }

    /**
     * Cancels the task identified by its request handle and waits for its execution to exit.
     *
     * @return whether the task settled within {@code timeout}; false if it is not owned by this
     *     runner or did not settle in time
     */
    public boolean cancelTask(@NonNull RequestHandle task, @NonNull Duration timeout)
            throws InterruptedException {
        boolean queuedCancellation = false;
        synchronized (this) {
            if (task.owner != this) return false;
            if (!task.resultClaimed && !task.result.isDone()) task.cancelled = true;
            if (task.cancelled && !task.resultClaimed && task != control.request()) {
                // Admission uses this same gate: this handle cannot start after this intent.
                task.resultClaimed = true;
                queuedCancellation = true;
            } else if (task.cancelled
                    && !task.resultClaimed
                    && task == control.request()
                    && !task.interruptSent) {
                task.interruptSent = true;
                toolBoundary.declineAll();
                var thread = executionThread;
                if (thread != null) thread.interrupt();
            }
        }
        if (queuedCancellation) {
            task.result.complete(AgentResult.failure("Task cancelled", Map.of()));
            task.settled.complete(true);
        }
        try {
            return task.settled.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Sets how tool results are rendered to the model for subsequent turns. */
    public void setToolResultPresentation(
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        requireSetup();
        this.toolResultPresentation = toolResultPresentation;
    }

    /** Attaches the durable store used to reload request continuations across restarts. */
    public void attachContinuationStore(@NonNull RequestContinuationStore store) {
        requireSetup();
        continuations.attachContinuationStore(store);
    }

    /** Attaches the plugin work source polled for autonomous observations. */
    public void attachWorkSource(@NonNull AgentInbox service) {
        handoff(new RunnerCommand.WorkSource(service));
    }

    /** Signals that plugin work may be available, waking the loop to claim it. */
    public void signalWork() {
        synchronized (this) {
            if (stopReason == null && control.open() && workQueued.compareAndSet(false, true))
                actionQueue.add(new RunnerCommand.Wake());
        }
    }

    /**
     * The agent's execution loop: drains the action queue on this (virtual) thread, running one
     * episode per dequeued request until the agent is terminated or the thread is interrupted.
     */
    void run() {
        if (Thread.currentThread() != executionThread)
            throw new IllegalStateException("Runner must execute on its attached thread");
        // Stamp the session owner onto the agent's virtual thread so credential resolution on the
        // LLM-call path (CredentialResolver → KeysteadVault.currentHandle → UserContext.get) and
        // the embedder path resolve against the owner's vault rather than the single-active-handle
        // fallback. Owner is set once by AgentService before this thread starts, so a single set at
        // entry covers every turn; clear on exit so the thread never leaks a stale user.
        String currentOwner = owner;
        if (currentOwner != null) {
            UserContext.set(currentOwner);
        }
        try {
            while (stopReason == null && control.open()) {
                try {
                    var parked = control.request();
                    if (parked != null && parked.cancelled && control.waiting(Wait.PLUGIN)) {
                        checkCancelledParked(parked);
                        continue;
                    }
                    var command = actionQueue.take();
                    QueuedRequest queued;
                    if (command instanceof RunnerCommand.Wake) {
                        workQueued.set(false);
                        var work = continuations.claimWork(control, this, baseBinding, locale);
                        if (work == null) continue;
                        queued = work;
                    } else if (command instanceof QueuedRequest request) {
                        queued = request;
                    } else {
                        if (stopReason == null) applyCommand(command);
                        continue;
                    }
                    AgentAction action = queued.action();
                    if (queued.handle().cancelled) {
                        if (control.request() == queued.handle())
                            checkCancelledParked(queued.handle());
                        else settleUnadmitted(queued.handle(), "Task cancelled");
                        continue;
                    }
                    if (!beginRequest(queued)) continue;
                    String prompt =
                            action instanceof AgentAction.UserPromptAction upa ? upa.prompt() : "";
                    RequestHandle taskCancellation = queued.handle();
                    setActivity(ExecutionControl.Activity.MODEL);
                    try {
                        checkTaskCancellation();
                        if (!(action instanceof AgentAction.CompactAction)) clearWait(Wait.PLUGIN);
                        checkExecutionBoundary();
                        if (!(action instanceof AgentAction.CompactAction)) resolveConfiguration();
                        if (action instanceof AgentAction.CompactAction) {
                            models.compact();
                        } else if (action instanceof AgentAction.WorkAction) {
                            // Consume readiness before admitting any new model/tool effects.
                            currentRequest().awaiting();
                            models.refreshSystemHistory();

                            if (injectObservations()) models.run(null, tools);
                        } else {
                            continuations.remember(currentRequest().episode);
                            var firstPrompt = models.userPrompt(prompt);
                            continuations.persistRequest(currentRequest());
                            models.run(firstPrompt, tools);
                        }
                        checkTaskCancellation();
                        if (action instanceof AgentAction.CompactAction)
                            complete(
                                    AgentResult.success(
                                            currentRequest().message,
                                            Map.of("turns", output.turnNumber())));
                        else if (currentRequest().awaiting()) {
                            beginWait(Wait.PLUGIN);
                            signalWork();
                        } else
                            complete(
                                    AgentResult.success(
                                            currentRequest().message,
                                            Map.of("turns", output.turnNumber())));
                    } catch (LinkageError error) {
                        completeFailure(output.failureMessage(error));
                        throw error;
                    } catch (BreakerTripException e) {
                        complete(
                                AgentResult.failure(
                                        currentRequest().message,
                                        Map.of("turns", output.turnNumber(), "breakerTrip", true)));
                    } catch (Exception e) {
                        if (taskCancellation.cancelled) {
                            synchronized (this) {
                                // Wait for cancelTask to finish sending the one interrupt.
                                clearTaskInterrupt();
                            }
                            completeFailure(
                                    Msg.get(locale(), "error.agent.taskCancelled"),
                                    true,
                                    taskCancellation.requestId());
                        } else {
                            log.error("Agent {} task failed", agentId, e);
                            completeFailure(output.failureMessage(e));
                        }
                    } finally {
                        finishTurn(taskCancellation, action instanceof AgentAction.CompactAction);
                    }
                } catch (InterruptedException e) {
                    if (stopReason != null) break;
                    RequestHandle parked = control.request();
                    if (parked == null || !parked.cancelled || !control.waiting(Wait.PLUGIN)) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // The interrupt merely wakes this owner; persistence and wait release stay
                    // here.
                    checkCancelledParked(parked);
                }
            }
        } catch (LinkageError error) {
            // The admitted request was failed before unwinding; a broken runtime must retire.
            log.error("Agent {} runtime linkage failed", agentId, error);
        } finally {
            // The stop signal ends execution; mandatory retirement still delivers its events.
            boolean interrupted = Thread.interrupted();
            try {
                retire();
            } finally {
                try {
                    UserContext.clear();
                } finally {
                    try {
                        notifyTermination();
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Seeds replayed history before the first episode (idempotent). If the replay shows an episode
     * left unfinished, the agent is parked in the {@code INTERRUPTED} wait so the next prompt
     * records an explicit continuation boundary.
     */
    public void seedHistory(@NonNull List<TurnRecord> replayed) {
        handoff(new RunnerCommand.History(replayed));
    }

    /** Whether the agent is running or has queued work still to process. */
    public boolean hasPendingWork() {
        if (stopReason != null || !control.open()) return false;
        return control.state() != AgentState.IDLE
                || actionQueue.stream()
                        .anyMatch(
                                action ->
                                        action instanceof QueuedRequest request
                                                && (request.action()
                                                                instanceof
                                                                AgentAction.UserPromptAction
                                                        || request.action()
                                                                instanceof
                                                                AgentAction.CompactAction));
    }

    /** Enqueues a new episode for {@code action} and returns the handle that owns its result. */
    public @NonNull RequestHandle startTask(
            Consumer<AgentResult> callback, @NonNull AgentAction action) {
        RunnerCommand.Inputs inputs;
        synchronized (this) {
            inputs = submissionInputs;
        }
        return startTask(callback, action, inputs.binding(), inputs.locale());
    }

    /**
     * Admits explicit request inputs atomically with the prompt, independent of later submissions.
     */
    public @NonNull RequestHandle startTask(
            Consumer<AgentResult> callback,
            @NonNull AgentAction action,
            @NonNull LlmBinding binding,
            @NonNull Locale locale) {
        RequestHandle handle;
        synchronized (this) {
            if (stopReason != null || !control.open())
                throw new IllegalStateException("Agent has terminated");
            var inputs = new RunnerCommand.Inputs(binding, locale);
            if (!inputs.equals(submissionInputs)) actionQueue.add(inputs);
            submissionInputs = inputs;
            RequestHandle previous = control.request();
            String promptText =
                    action instanceof AgentAction.UserPromptAction prompt ? prompt.prompt() : null;
            boolean continuation =
                    control.waiting(Wait.BREAKER)
                            && promptText != null
                            && "continue".equalsIgnoreCase(promptText.strip());
            handle =
                    continuation && previous != null
                            ? new RequestHandle(this, previous.episode, binding, locale)
                            : new RequestHandle(this, continuations.newEpisode(), binding, locale);
            if (callback != null) handle.result.thenAccept(callback);
            actionQueue.add(new QueuedRequest(action, handle));
        }
        output.events.executionChanged();
        return handle;
    }

    /** Replaces the base model binding used for subsequent configuration resolution. */
    public void bind(@NonNull LlmBinding binding) {
        synchronized (this) {
            if (stopReason != null || !control.open())
                throw new IllegalStateException("Agent has terminated");
            submissionInputs = new RunnerCommand.Inputs(binding, submissionInputs.locale());
            actionQueue.add(submissionInputs);
        }
    }

    /** The model binding currently in effect. */
    public @NonNull LlmBinding binding() {
        return executionConfiguration().binding();
    }

    /** The agent's current lifecycle state. */
    public @NonNull AgentState state() {
        return stopReason == null ? control.state() : AgentState.TERMINATED;
    }

    /** The tool names this agent is authorized to call. */
    public @NonNull Set<String> whitelistedToolsView() {
        return executionConfiguration().persona().whitelistedTools().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Sets the terminal policy and host effect guard applied to each episode. */
    public void setExecutionPolicy(@NonNull AgentExecutionPolicy policy) {
        var terminal = policy.terminal();
        if (terminal != null && !whitelistedToolsView().contains(terminal.tool()))
            throw new IllegalArgumentException("Terminal tool must be in the agent's whitelist");
        handoff(new RunnerCommand.Policy(policy));
    }

    /** Whether the current episode has consumed its model-call budget. */
    public boolean budgetExhausted() {
        RequestHandle request = control().request();
        return request != null && request.episode.breaker().shouldTrip();
    }

    /** Sets the locale used to render agent-thread messages (null resets to English). */
    public void setLocale(Locale locale) {
        synchronized (this) {
            if (stopReason != null || !control.open())
                throw new IllegalStateException("Agent has terminated");
            submissionInputs =
                    new RunnerCommand.Inputs(
                            submissionInputs.binding(), locale == null ? Locale.ENGLISH : locale);
            actionQueue.add(submissionInputs);
        }
    }

    /** The locale currently used for agent-thread messages. */
    public @NonNull Locale locale() {
        var request = control.request();
        return request == null ? locale : request.locale;
    }

    /** The agent identity fixed at construction, independent of plugin execution profiles. */
    public @NonNull AgentPersona personaView() {
        return persona;
    }

    /** Attaches the session's resolved plugin selection. */
    public void attachSessionPlugins(@NonNull SessionPlugins value) {
        requireSetup();
        sessionPlugins = value;
    }

    /** Startup-only attachment. Thread.start publishes this write to the execution loop. */
    private void requireSetup() {
        if (executionThread != null)
            throw new IllegalStateException("Attach dependencies before execution starts");
    }

    void attachExecutionThread(@NonNull Thread thread) {
        if (executionThread != null)
            throw new IllegalStateException("Execution thread already attached");
        executionThread = thread;
    }

    void onTermination(@NonNull Runnable callback) {
        synchronized (this) {
            if (!terminationNotified) {
                var previous = terminationCallback;
                terminationCallback =
                        previous == null
                                ? callback
                                : () -> {
                                    try {
                                        previous.run();
                                    } finally {
                                        callback.run();
                                    }
                                };
                return;
            }
        }
        callback.run();
    }

    /** The session this runner belongs to. */
    public @NonNull UUID sessionId() {
        return sessionId;
    }

    /** Closes the runner for host shutdown, interrupting any in-flight episode. */
    public void shutdown() {
        close(ExecutionControl.CloseReason.SHUTDOWN);
    }

    /** Requests termination of the agent (agent deleted); the loop exits at its next boundary. */
    public void terminate() {
        close(ExecutionControl.CloseReason.AGENT_DELETED);
    }

    /** Attaches the host dispatcher used by agent workflow and lifecycle event producers. */
    public void attachEventManager(@NonNull EventManager events) {
        requireSetup();
        eventManager = events;
    }

    @NonNull ExecutionControl control() {
        return control;
    }

    @NonNull String currentTask() {
        var request = control.request();
        return request == null ? "" : request.episode.task();
    }

    long configurationRevision() {
        return configurationRevision;
    }

    AgentOutput.@NonNull View outputView() {
        var snapshot = control;
        return new AgentOutput.View(
                snapshot.request(),
                toolResultPresentation,
                snapshot.waiting(Wait.QUESTION),
                binding().options().contextWindowOrDefault(),
                locale());
    }

    private AgentProfiles.@NonNull Resolved executionConfiguration() {
        var request = control.request();
        var configuration = request == null ? null : request.configuration;
        return configuration == null
                ? new AgentProfiles.Resolved(
                        persona,
                        request != null && request.binding != null ? request.binding : baseBinding,
                        null)
                : configuration;
    }

    ModelSession.@NonNull Configuration modelConfiguration() {
        var configuration = executionConfiguration();
        return new ModelSession.Configuration(
                configuration.persona(),
                configuration.binding(),
                configuration.prompt(),
                toolResultPresentation,
                owner,
                modelTierRegistry,
                executionPolicy.terminal(),
                toolBoundary.workspace());
    }

    AgentToolExecution.@NonNull Invocation toolInvocation() {
        return new AgentToolExecution.Invocation(
                currentRequest(),
                toolResultPresentation,
                whitelistedToolsView(),
                executionPolicy,
                configurationRevision);
    }

    boolean injectObservations() {
        checkExecutionBoundary();
        return continuations.injectObservations(currentRequest());
    }

    boolean beginRequest(@NonNull QueuedRequest queued) {
        boolean rejected;
        synchronized (this) {
            rejected = stopReason != null || !control.open() || queued.handle().cancelled;
            if (!rejected && !control.waiting(Wait.PLUGIN))
                control = control.withRequest(queued.handle());
            else if (!rejected && queued.action() instanceof AgentAction.WorkAction)
                control = control.withRequest(queued.handle());
        }
        if (rejected) {
            settleUnadmitted(
                    queued.handle(),
                    queued.handle().cancelled ? "Task cancelled" : "Agent terminated");
            return false;
        }
        if (control.request() != queued.handle()) {
            deferredUserPrompts.add(queued);
            signalWork();
            return false;
        }
        toolBoundary.locale(queued.handle().locale);
        return true;
    }

    void finishTurn(@NonNull RequestHandle request, boolean compact) {
        boolean parked;
        synchronized (this) {
            parked = !compact && control.request() == request && control.waiting(Wait.PLUGIN);
            if (control.request() == request
                    && (compact || !control.waiting(Wait.PLUGIN) && !control.waiting(Wait.BREAKER)))
                control = control.withRequest(null);
            // Exclude cancellation's one interrupt before clearing the old request's signal.
            clearTaskInterrupt();
        }
        if (!parked) request.settled.complete(true);
        output.events.executionChanged();
    }

    private void checkCancelledParked(@NonNull RequestHandle request) {
        synchronized (this) {
            clearTaskInterrupt();
        }
        completeFailure("Task cancelled", true, request.requestId());
        finishTurn(request, false);
    }

    void beginWait(@NonNull Wait reason) {
        var current = control;
        if (!current.open()) return;
        var activity =
                current instanceof ExecutionControl.Executing executing
                        ? executing.activity()
                        : current instanceof ExecutionControl.Suspended suspended
                                ? suspended.activity()
                                : ExecutionControl.Activity.MODEL;
        control = new ExecutionControl.Suspended(current.request(), activity, reason);
        output.events.executionChanged();
    }

    void clearWait(@NonNull Wait reason) {
        if (!(control instanceof ExecutionControl.Suspended suspended)
                || suspended.reason() != reason) return;
        control =
                suspended.request() == null
                        ? new ExecutionControl.Idle()
                        : new ExecutionControl.Executing(suspended.request(), suspended.activity());
        output.events.executionChanged();
    }

    void checkExecutionBoundary() {
        executionPolicy.check().run();
        if (stopReason != null || !control.open())
            throw new CancellationException("Agent terminated");
        checkTaskCancellation();
        RequestHandle request = control.request();
        if (request != null) request.awaiting();
    }

    @NonNull RequestHandle currentRequest() {
        return Nullness.requireNonNull(control.request(), "No executing request");
    }

    void checkTaskCancellation() {
        synchronized (this) {
            RequestHandle task = control.request();
            if (task != null && task.cancelled) {
                // Cancellation remains recorded on the task; cleanup must not inherit the signal
                // and close database sockets while persisting the cancelled outcome.
                clearTaskInterrupt();
                throw new CancellationException("Task cancelled");
            }
        }
    }

    static void clearTaskInterrupt() {
        if (Thread.interrupted()) {
            log.debug("Cleared task interrupt before runner cleanup");
        }
    }

    void tripBreaker() {
        beginWait(Wait.BREAKER);
        output.breaker(currentRequest());
    }

    void completeFailure(String message) {
        RequestHandle request = control.request();
        completeFailure(message, false, request == null ? null : request.episode.id());
    }

    void completeFailure(String message, boolean cancelled, String request) {
        complete(output.failure(message, cancelled, request));
    }

    void complete(@NonNull AgentResult result) {
        RequestHandle task;
        synchronized (this) {
            task = control.request();
            if (task != null) task.resultClaimed = true;
            if (task != null && task.cancelled)
                result = AgentResult.failure("Task cancelled", Map.of());
        }
        clearWait(Wait.APPROVAL);
        clearWait(Wait.QUESTION);
        clearWait(Wait.PLUGIN);
        if (task != null) continuations.complete(task, result);
        output.completed(task, result);
        actionQueue.addAll(deferredUserPrompts);
        deferredUserPrompts.clear();
        if (task != null) {
            continuations.settled(task.episode);
            task.releaseWaits();
            task.result.complete(result);
        }
    }

    void setActivity(ExecutionControl.@NonNull Activity activity) {
        var current = control;
        if (!current.open() || current instanceof ExecutionControl.Suspended) return;
        control = new ExecutionControl.Executing(current.request(), activity);
        output.events.executionChanged();
    }

    void resolveConfiguration() {
        var request = control.request();
        if (request == null) return;
        var binding = request.binding == null ? baseBinding : request.binding;
        var selected =
                AgentProfiles.select(
                        persona,
                        binding,
                        sessionPlugins,
                        owner,
                        sessionId.toString(),
                        agentId,
                        toolEngine,
                        modelTierRegistry,
                        currentTask());
        var transition = selected.transition();
        boolean changing = transition != null && !transition.key().equals(configurationTransition);
        String summary = changing ? models.summarizeTransition() : "";
        if (!executionConfiguration().equals(selected.resolved())) configurationRevision++;
        request.configuration = selected.resolved();
        if (changing) {
            var value = Nullness.requireNonNull(transition);
            models.restart(summary);
            output.appendTurn(
                    PromptCompiler.sourcedUserPrompt(
                            output.nextTurn(), value.prompt(), JsonValues.toMap(value.data())));
            configurationTransition = value.key();
        }
    }

    void notifyTermination() {
        Runnable callback;
        synchronized (this) {
            if (terminationNotified) return;
            terminationNotified = true;
            callback = terminationCallback;
            terminationCallback = null;
        }
        try {
            var events = eventManager;
            String currentOwner = owner;
            var snapshot = control;
            if (snapshot instanceof ExecutionControl.Closed closed
                    && closed.reason() != ExecutionControl.CloseReason.SHUTDOWN
                    && events != null
                    && currentOwner != null)
                events.submit(
                        new AgentTerminatedEvent(
                                new Scope.AgentScope(currentOwner, sessionId.toString(), agentId)));
        } finally {
            if (callback != null) callback.run();
        }
    }

    void close(ExecutionControl.@NonNull CloseReason reason) {
        RequestHandle stopped = null;
        synchronized (this) {
            if (stopReason != null || !control.open()) return;
            stopReason = reason;
            RequestHandle active = control.request();
            if (active != null && !active.resultClaimed) {
                active.cancelled = true;
                active.resultClaimed = true;
                stopped = active;
            }
            toolBoundary.declineAll();
            var thread = executionThread;
            if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        }
        if (stopped != null) {
            // Unblock a waiting parent even when its child provider ignores interruption.
            // This announces rejection, not execution exit; only the owner releases waits/settles.
            stopped.result.complete(
                    AgentResult.failure(
                            Msg.get(stopped.locale, "error.agent.interrupted"), Map.of()));
        }
    }

    private void retire() {
        RequestHandle active;
        Locale closingLocale = locale();
        synchronized (this) {
            var reason = stopReason;
            if (reason == null) {
                reason = ExecutionControl.CloseReason.AGENT_DELETED;
                stopReason = reason;
            }
            active = control.request();
            control = new ExecutionControl.Closed(reason);
        }
        String message = Msg.get(closingLocale, "error.agent.interrupted");
        if (active != null) {
            active.releaseWaits();
            // A result claimant may have been interrupted before delivery; retirement cannot
            // leave the active caller waiting after execution has actually stopped.
            active.result.complete(AgentResult.failure(message, Map.of()));
            active.settled.complete(true);
        }
        for (var request : deferredUserPrompts) settleUnadmitted(request.handle(), message);
        deferredUserPrompts.clear();
        RunnerCommand command;
        while ((command = actionQueue.poll()) != null) {
            if (command instanceof QueuedRequest queued) settleUnadmitted(queued.handle(), message);
        }
        toolBoundary.clear();
        output.events.executionChanged();
    }

    private void settleUnadmitted(@NonNull RequestHandle request, @NonNull String message) {
        synchronized (this) {
            if (request.resultClaimed) return;
            request.resultClaimed = true;
        }
        request.result.complete(AgentResult.failure(message, Map.of()));
        request.settled.complete(true);
    }

    private void handoff(@NonNull RunnerCommand command) {
        boolean inline;
        synchronized (this) {
            if (stopReason != null || !control.open())
                throw new IllegalStateException("Agent has terminated");
            inline = executionThread == null || Thread.currentThread() == executionThread;
            if (!inline) actionQueue.add(command);
        }
        if (inline) applyCommand(command);
    }

    private void applyCommand(@NonNull RunnerCommand command) {
        switch (command) {
            case RunnerCommand.Inputs inputs -> {
                baseBinding = inputs.binding();
                locale = inputs.locale();
            }
            case RunnerCommand.History history -> {
                if (output.seedHistory(history.turns())) beginWait(Wait.INTERRUPTED);
            }
            case RunnerCommand.WorkSource source -> continuations.attachWorkSource(source.inbox());
            case RunnerCommand.Policy policy -> executionPolicy = policy.policy();
            case RunnerCommand.Wake ignored ->
                    throw new IllegalArgumentException("Wake requires work claim");
            case QueuedRequest ignored ->
                    throw new IllegalArgumentException("Request requires admission");
        }
    }
}
