package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.CredentialException;
import top.focess.veto.api.llm.exceptions.LlmAuthException;
import top.focess.veto.api.llm.exceptions.LlmRateLimitException;
import top.focess.veto.api.llm.exceptions.LlmTimeoutException;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.bus.DeltaFrame;
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
    private final @NonNull ToolExecutionBoundary toolBoundary;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AgentPersona persona;
    // Host submissions may update these from transport threads; execution observes them.
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

    private final @NonNull AgentPluginHooks hooks;
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
    private EventManager eventManager;
    private final @NonNull AgentContinuationExecution continuations;
    private final @NonNull AgentToolExecution tools;
    private final @NonNull ModelSession models;
    private final @NonNull AgentOutput output;
    private final String owner;
    private final @NonNull ModelFlowStack flowStack = new ModelFlowStack();

    private static final class FlowStepControl {
        private boolean finish;
    }

    /** Creates a runner with no session owner, deriving the session id from the persona id. */
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
            TurnLogService turnLogService) {
        this(
                agentId,
                persona,
                toolEngine,
                toolBoundary,
                interceptors,
                promptCompiler,
                caller,
                objectMapper,
                maxCallsPerEpisode,
                binding,
                eventSink,
                userId,
                turnLogService,
                null,
                UUID.fromString(agentId));
    }

    /**
     * Creates a runner bound to an explicit session owner and session id. Does not start the loop;
     * submit {@link #run()} to a thread (a virtual thread per {@link VetoAgent}) to begin draining
     * the action queue.
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
        this.objectMapper = objectMapper;
        this.persona = persona;
        this.baseBinding = binding;
        this.submissionInputs = new RunnerCommand.Inputs(binding, Locale.ENGLISH);
        actionQueue = new LinkedBlockingQueue<>();
        output =
                new AgentOutput(
                        new AgentHistory(turnLogService, () -> sessionId, userId, agentId),
                        new AgentEvents(agentId, objectMapper, eventSink, () -> sessionId),
                        promptCompiler,
                        new ToolResultPresenter(objectMapper),
                        toolEngine,
                        this::outputView);
        hooks =
                new AgentPluginHooks(
                        owner,
                        sessionId.toString(),
                        agentId,
                        objectMapper,
                        caller,
                        this::sessionPlugins,
                        this::eventManager,
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
                        () -> currentRequest().episode.breaker(),
                        this::modelConfiguration,
                        this::checkExecutionBoundary,
                        this::reserveRequestCall,
                        this::tripBreaker);
        continuations =
                new AgentContinuationExecution(
                        agentId,
                        sessionId,
                        owner,
                        maxCallsPerEpisode,
                        output,
                        actionQueue,
                        this::sessionPlugins);
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

    /** The plugin provenance snapshot for the agent's latest model context. */
    public @NonNull PluginContextSnapshot pluginContext() {
        return models.pluginContext();
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
                        var work = claimWork();
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
                            processCompaction();
                        } else if (action instanceof AgentAction.WorkAction) {
                            // Consume readiness before admitting any new model/tool effects.
                            currentRequest().awaiting();
                            models.refreshSystemHistory();

                            if (injectObservations()) runAutonomous(null);
                        } else {
                            var firstPrompt = processUserPrompt(prompt);
                            runAutonomous(firstPrompt);
                        }
                        checkTaskCancellation();
                        if (action instanceof AgentAction.CompactAction) completeSuccess();
                        else completeOrWaitForWork();
                    } catch (LinkageError error) {
                        completeFailure(failureMessage(error));
                        throw error;
                    } catch (BreakerTripException e) {
                        completeBreaker();
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
                            completeFailure(failureMessage(e));
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
                retire(
                        stopReason == null
                                ? ExecutionControl.CloseReason.AGENT_DELETED
                                : stopReason);
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

    void runAutonomous(ModelSession.Prepared firstPrompt) {
        ToolCallContextHolder.ResponseDirective pending = null;
        String originatingCall = null;
        while (control().state() == AgentState.RUNNING) {
            checkTaskCancellation();
            // Mid-episode task lifecycle: a background task that ended (or that the user
            // stopped) during THIS episode is reported at the next iteration, not only at the
            // start of the next episode. Cheap no-op when the queue is empty.
            // processUserPrompt already drained notices before preparing the first immutable
            // request. Do not mutate history between that compilation and its dispatch.
            if (firstPrompt == null) {
                injectObservations();
            }
            if (pending == null && currentRequest().episode.breaker().shouldTrip()) {
                tripBreaker();
                throw new BreakerTripException();
            }
            var accepted = pending;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Push pushed) {
                pending = null;
                checkTaskCancellation();
                flowStack.push(pushed.flow());
            }
            if (!flowStack.defaultSelected()) {
                ModelFlow flow = flowStack.top();
                long configuration = configurationRevision();
                var sources = new RequestEvidence.WorkSources();
                var control = new FlowStepControl();
                try {
                    flow.run(workRuntime(originatingCall, sources, flow, control));
                } catch (RuntimeException | Error failure) {
                    flowStack.discardFailed(flow);
                    throw failure;
                } finally {
                    sources.close();
                }
                if (configurationRevision() != configuration) continue;
                if (control.finish) return;
                if (!flowStack.defaultSelected() && flowStack.top() == flow) return;
                continue;
            }
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer) {
                if (answer.publish()) {
                    checkTaskCancellation();
                    output.emitMessage(
                            answer.message(),
                            RequestEvidence.forRequest(
                                    answer.citations(),
                                    output.requestIdentity(),
                                    originatingCall,
                                    answer.message()),
                            originatingCall,
                            false);
                } else {
                    currentRequest().message = answer.message();
                }
                return;
            }
            var exchange = models.callModel(firstPrompt);
            firstPrompt = null;
            VetoResponse response = exchange.response();
            originatingCall = exchange.modelCallId();
            checkTaskCancellation();

            output.appendThought(response, exchange.modelCallId());
            String message = response.message();
            if (message != null && !message.isBlank()) {
                var calls = response.calls();
                output.emitMessage(
                        message,
                        RequestEvidence.forRequest(
                                exchange.citations(),
                                output.requestIdentity(),
                                exchange.modelCallId(),
                                message),
                        exchange.modelCallId(),
                        false,
                        calls != null
                                && calls.stream().anyMatch(call -> call.nativeState() != null));
            }
            List<ToolCall> responseCalls = response.calls();
            if (responseCalls != null && !responseCalls.isEmpty()) {
                pending =
                        tools.executeToolCalls(
                                responseCalls,
                                response.thought(),
                                new ToolBatch(exchange, false, null),
                                toolInvocation());
            } else {
                // No tool calls: the agent has emitted its answer with nothing further to act
                // on. Termination routes on call presence - calls absent means stop. The agent
                // reasons within its model invocation. Stop the episode here; the emitted
                // message is the final answer.
                return;
            }
        }
    }

    ModelFlow.@NonNull Runtime workRuntime(
            String sourceCallId,
            RequestEvidence.@NonNull WorkSources sources,
            @NonNull ModelFlow flow,
            @NonNull FlowStepControl control) {
        long configuration = configurationRevision();
        return new ModelFlow.Runtime() {
            @Override
            public @NonNull String input() {
                sources.check();
                return currentRequest().episode.task();
            }

            @Override
            public void push(@NonNull ModelFlow child) {
                sources.check();
                checkTaskCancellation();
                if (!running()) throw new IllegalStateException("Flow request is no longer active");
                flowStack.pushNested(flow, child);
            }

            @Override
            public void pop() {
                sources.check();
                flowStack.pop(flow);
            }

            @Override
            public void finish() {
                sources.check();
                control.finish = true;
            }

            @Override
            public void beforeStep() {
                sources.check();
                checkTaskCancellation();
                injectObservations();
            }

            @Override
            public boolean running() {
                return sources.active()
                        && control().state() == AgentState.RUNNING
                        && configurationRevision() == configuration;
            }

            @Override
            public @NonNull ToolResult tool(
                    @NonNull ToolCall call, @NonNull ActionContext context) {
                sources.check();
                return tools.executeOneCall(
                        call, new ToolBatch(null, false, context), toolInvocation());
            }

            @Override
            public ModelFlow.@NonNull Generated generate(
                    ModelFlow.@NonNull ModelInput action, @NonNull ResponseContract contract) {
                sources.check();
                ModelExchange.Result exchange = callGenerate(action, contract);
                sources.register(exchange.citations());
                return new ModelFlow.Generated(
                        exchange.response(), exchange.citations(), exchange.modelCallId());
            }

            @Override
            public void message(
                    @NonNull String text,
                    ModelFlow.Source citations,
                    String callId,
                    boolean forwarded) {
                output.emitMessage(
                        text,
                        sources.consume(citations, output.requestIdentity(), callId, text),
                        callId,
                        forwarded);
            }

            @Override
            public @NonNull String prompt(
                    @NonNull String source, @NonNull Map<String, Object> data) {
                return PromptCompiler.compileText(source, data);
            }

            @Override
            public void observation(@NonNull String topic, @NonNull String reason) {
                sources.check();
                output.appendObservation(topic, reason);
            }

            public String sourceCallId() {
                return sourceCallId;
            }
        };
    }

    ModelExchange.@NonNull Result callGenerate(
            ModelFlow.@NonNull ModelInput gen, @NonNull ResponseContract contract) {
        if (currentRequest().episode.breaker().shouldTrip()) {
            tripBreaker();
            throw new BreakerTripException();
        }
        while (true) {
            ModelExchange.Result exchange = models.callModel(null, gen, contract);
            VetoResponse response = exchange.response();
            if (!Boolean.FALSE.equals(gen.thought()))
                output.appendThought(response, exchange.modelCallId());
            var calls = response.calls();
            if (calls == null || calls.isEmpty()) return exchange;
            var accepted =
                    tools.executeToolCalls(
                            calls,
                            response.thought(),
                            new ToolBatch(exchange, true, null),
                            toolInvocation());
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer)
                return new ModelExchange.Result(
                        exchange.request(),
                        RequestEvidence.response(
                                answer.message(),
                                answer.citations(),
                                output.requestIdentity(),
                                exchange.modelCallId()),
                        answer.citations(),
                        exchange.modelCallId(),
                        true);
            checkTaskCancellation();
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
        notifyExecutionChanged();
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

    /** Output owns subscriptions independently of execution scheduling. */
    @NonNull AgentOutput output() {
        return output;
    }

    /** The agent's current lifecycle state. */
    public @NonNull AgentState state() {
        return stopReason == null ? control.state() : AgentState.TERMINATED;
    }

    /** The durable turn history, oldest first. */
    public @NonNull List<TurnRecord> history() {
        return output.history();
    }

    /** The read-history used for drift detection. */
    public @NonNull ReadHistory readHistory() {
        return toolBoundary.readHistory();
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

    /** The agent's stable identity (its persona id). */
    public @NonNull String agentId() {
        return agentId;
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
                binding().options().contextWindowOrDefault());
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

    EventManager eventManager() {
        return eventManager;
    }

    SessionPlugins sessionPlugins() {
        return sessionPlugins;
    }

    void reserveRequestCall() {
        continuations.reserveRequestCall(currentRequest());
    }

    boolean injectObservations() {
        checkExecutionBoundary();
        return continuations.injectObservations(currentRequest());
    }

    void completeOrWaitForWork() {
        if (currentRequest().awaiting()) {
            beginWait(Wait.PLUGIN);
            signalWork();
        } else completeSuccess();
    }

    QueuedRequest claimWork() {
        workQueued.set(false);
        var queued = continuations.claimWork(control, this, baseBinding, locale);
        if (queued == null) return null;
        return queued;
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
        notifyExecutionChanged();
    }

    private void checkCancelledParked(@NonNull RequestHandle request) {
        synchronized (this) {
            clearTaskInterrupt();
        }
        completeFailure("Task cancelled", true, request.requestId());
        finishTurn(request, false);
    }

    void beginWait(@NonNull Wait reason) {
        if (!control.open()) return;
        var activity =
                control instanceof ExecutionControl.Executing executing
                        ? executing.activity()
                        : control instanceof ExecutionControl.Suspended suspended
                                ? suspended.activity()
                                : ExecutionControl.Activity.MODEL;
        control = new ExecutionControl.Suspended(control.request(), activity, reason);
        notifyExecutionChanged();
    }

    void clearWait(@NonNull Wait reason) {
        if (!(control instanceof ExecutionControl.Suspended suspended)
                || suspended.reason() != reason) return;
        control =
                suspended.request() == null
                        ? new ExecutionControl.Idle()
                        : new ExecutionControl.Executing(suspended.request(), suspended.activity());
        notifyExecutionChanged();
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

    ModelSession.@NonNull Prepared processUserPrompt(@NonNull String prompt) {
        continuations.remember(currentRequest().episode);
        prompt = hooks.captureUserPrompt(hooks.beforeInput(hooks.captureUserPrompt(prompt)));
        if (control.waiting(Wait.INTERRUPTED)) {
            output.appendTurn(
                    new TurnRecord(
                            output.nextTurn(),
                            TurnType.EXECUTION_ERROR,
                            Map.of(
                                    "outcome",
                                    "INTERRUPTED",
                                    "content",
                                    "The previous execution was interrupted by a backend restart."
                                            + " Its uncompleted plans are not pending; tool effects"
                                            + " without recorded results remain unknown."),
                            null));
        }

        currentRequest().declinedCallSignatures.clear();
        // Drain plugin observations into the context before the new user prompt so the model
        // reads them together. Plugins publish any live UI updates through generic plugin events.
        // Fresh UserPromptAction: reset plan state and program counter. An exact "continue" has
        // special semantics only immediately after a breaker trip. Preserve the literal user input
        // in history while attaching the prior task for prompt compilation; otherwise a long,
        // budget-trimmed episode re-anchors on the context-free word "continue".
        String resumeContext =
                control.waiting(Wait.BREAKER) && "continue".equalsIgnoreCase(prompt.strip())
                        ? (currentTask().isBlank() ? latestUserTaskContext() : currentTask())
                        : null;
        currentRequest().episode.task(resumeContext != null ? resumeContext : prompt);
        currentRequest().episode.observationId(null);
        clearWait(Wait.BREAKER);
        clearWait(Wait.INTERRUPTED);
        injectObservations();

        models.refreshSystemHistory();
        TurnRecord prospectiveUserTurn =
                resumeContext != null
                        ? TurnRecord.breakerContinuation(
                                output.turnNumber() + 1, prompt, resumeContext)
                        : TurnRecord.userPrompt(output.turnNumber() + 1, prompt);
        List<TurnRecord> prospectiveHistory = new ArrayList<>(output.history());
        prospectiveUserTurn = withRequestId(prospectiveUserTurn);
        prospectiveHistory.add(prospectiveUserTurn);
        var firstPrompt = models.preparePrompt(prospectiveHistory, false);
        output.appendTurn(
                withRequestId(
                        resumeContext != null
                                ? TurnRecord.breakerContinuation(
                                        output.nextTurn(), prompt, resumeContext)
                                : TurnRecord.userPrompt(output.nextTurn(), prompt)));
        if (resumeContext != null) currentRequest().episode.breaker().grantContinuation();
        continuations.persistRequest(control.request());

        return firstPrompt;
    }

    @NonNull TurnRecord withRequestId(@NonNull TurnRecord turn) {
        Map<@NonNull String, @Nullable Object> payload = new LinkedHashMap<>(turn.payload());
        String requestId = currentRequest().episode.id();
        payload.put("requestId", requestId);
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    String latestUserTaskContext() {
        List<TurnRecord> history = output.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord turn = history.get(i);
            if (turn.type() != TurnType.USER_PROMPT) {
                continue;
            }
            Object resumed = turn.payload().get("resume_context");
            if (resumed instanceof String text && !text.isBlank()) {
                return text;
            }
            Object content = turn.payload().get("content");
            if (content instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    void processCompaction() {
        int lastInitIndex = -1;
        List<TurnRecord> history = output.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).type() == TurnType.AGENT_INIT) {
                lastInitIndex = i;
                break;
            }
        }
        int anchorIndex = lastInitIndex != -1 ? lastInitIndex : 0;

        List<TurnRecord> workTurns = new ArrayList<>();
        if (anchorIndex >= history.size() - 1) {
            output.emitMessage(Msg.get(locale(), "error.agent.compactNothing"));
            return;
        }
        for (int i = anchorIndex + 1; i < history.size(); i++) {
            workTurns.add(history.get(i));
        }

        String finalSummary = computeCompactionSummary(workTurns);
        if ("{}".equals(finalSummary)) {
            output.appendObservation(
                    "compaction_failed",
                    "No valid summary was produced; the context was retained.");
            return;
        }

        output.appendTurn(TurnRecord.rewind(output.nextTurn(), 0));
        models.appendAgentInit(models.linkCurrentSystemMessage());
        output.appendTurn(TurnRecord.compactionSummary(output.nextTurn(), finalSummary));
        output.emitMessage(Msg.get(locale(), "error.agent.compactDone", workTurns.size()));
        // Domain event: the session compacted. Subscribers can mark the ledger boundary without
        // inferring it from the message text.
        output.publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.COMPACTION)
                        .attr("turnNumber", output.turnNumber())
                        .attr("compactedTurns", workTurns.size())
                        .text(finalSummary)
                        .build());
    }

    @NonNull String computeCompactionSummary(@NonNull List<TurnRecord> workTurns) {
        return new HistoryCompactor(
                        objectMapper,
                        (system, user) -> models.requests().compactionRequest(system, user),
                        this::performCompactionCall)
                .summarize(workTurns);
    }

    static void clearTaskInterrupt() {
        if (Thread.interrupted()) {
            log.debug("Cleared task interrupt before runner cleanup");
        }
    }

    @NonNull VetoResponse performCompactionCall(@NonNull VetoRequest request) {
        VetoResponse response;
        LlmSystemUsage.begin();
        try {
            checkTaskCancellation();
            response = hooks.callModelWithHooks(request);
            checkTaskCancellation();
        } finally {
            for (LlmSystemUsage.Usage measured : LlmSystemUsage.drain()) {
                UsageMeasurement data =
                        UsageMeasurement.measured(request, measured).forCompaction();
                output.recordUsage(output.turnNumber(), data);
            }
        }
        return response;
    }

    void completeSuccess() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", output.turnNumber());
        complete(AgentResult.success(currentRequest().message, meta));
    }

    void completeBreaker() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("breakerTrip", true);
        meta.put("turns", output.turnNumber());
        complete(AgentResult.failure(currentRequest().message, meta));
    }

    void tripBreaker() {
        beginWait(Wait.BREAKER);
        String notice = LoopBreaker.tripNotice(locale());
        output.emitMessage(notice);
        output.publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.BREAKER_TRIPPED)
                        .attr("turnNumber", output.turnNumber())
                        .attr(
                                "maxCallsPerEpisode",
                                currentRequest().episode.breaker().maxCallsPerEpisode())
                        .text(notice)
                        .build());
    }

    void completeFailure(String message) {
        RequestHandle request = control.request();
        completeFailure(message, false, request == null ? null : request.episode.id());
    }

    void completeFailure(String message, boolean cancelled, String request) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("content", message == null ? "" : message);
        if (cancelled) failure.put("outcome", "CANCELLED");
        if (request != null) failure.put("requestId", request);
        output.appendTurn(
                new TurnRecord(output.nextTurn(), TurnType.EXECUTION_ERROR, failure, null));
        // Domain event: the episode failed. Subscribers that surface an error banner use this; the
        // EPISODE_DONE below (success=false) is the authoritative "stop waiting" signal.
        output.publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.ERROR)
                        .attr("turnNumber", output.turnNumber())
                        .text(message == null ? "" : message)
                        .build());
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", output.turnNumber());
        complete(AgentResult.failure(message == null ? "" : message, meta));
    }

    @NonNull String failureMessage(@NonNull Throwable e) {
        // Pre-pass: a locked vault wins over any wrapper (CredentialException nests it).
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KeysteadVault.VaultLockedException) {
                return Msg.get(locale(), "error.agent.vaultLocked");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof VetoRefusedException refused) {
                return Msg.get(
                        locale(),
                        refused.approvalRequested
                                ? "error.agent.approvalNotGranted"
                                : "error.agent.vetoRefused");
            }
            if (t instanceof CredentialException) {
                return Msg.get(locale(), "error.agent.credentialMissing");
            }
            if (t instanceof LlmTimeoutException) {
                return Msg.get(locale(), "error.agent.llmTimeout");
            }
            if (t instanceof LlmRateLimitException) {
                return Msg.get(locale(), "error.agent.llmRateLimit");
            }
            if (t instanceof LlmAuthException) {
                return Msg.get(locale(), "error.agent.llmAuth");
            }
            if (t instanceof ModelSchemaException) {
                return Msg.get(locale(), "error.agent.llmSchema", String.valueOf(t.getMessage()));
            }
            if (t instanceof ModelCapabilityException mce) {
                // The same type covers transport call failures and unparseable responses
                // (AbstractLlmProvider); discriminate on the fixed message prefix.
                String detail = String.valueOf(mce.getMessage());
                if (detail.contains("could not be parsed")) {
                    return Msg.get(locale(), "error.agent.llmParse");
                }
                return Msg.get(locale(), "error.agent.llmCallFailed", detail);
            }
            if (t instanceof IllegalStateException
                    && String.valueOf(t.getMessage()).contains("embed")) {
                // ProviderEmbedder failures surface as IllegalStateException (best-effort memory).
                return Msg.get(locale(), "error.agent.embedFailed", String.valueOf(t.getMessage()));
            }
        }
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return Msg.get(locale(), "error.agent.taskFailed", detail);
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
        // Domain event: the episode finished. Carries the authoritative success flag so
        // subscribers
        // (the web UI, the terminal adapter) can stop waiting on the episode without blocking
        // on
        // the
        // submit call. Emitted before the future completes so a subscriber that also awaits the
        // future sees the event first.
        output.publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.EPISODE_DONE)
                        .attr("requestId", task == null ? "" : task.episode.id())
                        .attr("turnNumber", output.turnNumber())
                        .attr("success", result.success())
                        .text(result.message())
                        .build());
        actionQueue.addAll(deferredUserPrompts);
        deferredUserPrompts.clear();
        if (task != null) {
            continuations.settled(task.episode);
            task.releaseWaits();
            task.result.complete(result);
        }
    }

    void setActivity(ExecutionControl.@NonNull Activity activity) {
        if (!control.open() || control instanceof ExecutionControl.Suspended) return;
        control = new ExecutionControl.Executing(control.request(), activity);
        notifyExecutionChanged();
    }

    void notifyExecutionChanged() {
        output.publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.SESSION_INVALIDATED)
                        .attr("agentId", agentId)
                        .attr(
                                "resources",
                                objectMapper.createArrayNode().add("agents").add("execution"))
                        .build());
    }

    void resolveConfiguration() {
        var request = control.request();
        if (request == null) return;
        var binding = request.binding == null ? baseBinding : request.binding;
        var selection = sessionPlugins;
        String currentOwner = owner;
        if (selection == null || currentOwner == null) {
            request.configuration = new AgentProfiles.Resolved(persona, binding, null);
            return;
        }
        var available =
                selection.tools(sessionId.toString(), Set.copyOf(toolEngine.getActiveTools(null)));
        var original = persona;
        var base =
                new AgentProfile(
                        original.name(),
                        original.description(),
                        original.role().name(),
                        original.whitelistedTools().stream()
                                .map(ToolDefinition::name)
                                .collect(Collectors.toSet()),
                        null,
                        null,
                        Map.of());
        var intent =
                selection.configure(
                        currentOwner,
                        sessionId.toString(),
                        agentId,
                        original.configurationOwner(),
                        base,
                        available.stream()
                                .map(tool -> AgentProfiles.configurationTool(tool))
                                .toList(),
                        currentTask());
        if (intent == null) {
            var tools = selection.tools(sessionId.toString(), persona.whitelistedTools());
            var resolved =
                    new AgentProfiles.Resolved(persona.withWhitelistedTools(tools), binding, null);
            if (!executionConfiguration().equals(resolved)) configurationRevision++;
            request.configuration = resolved;
            return;
        }
        var profile = intent.profile();
        var tiers = modelTierRegistry;
        if (tiers == null) throw new IllegalStateException("Model tiers unavailable");
        var resolved =
                AgentProfiles.resolve(agentId, currentOwner, profile, available, binding, tiers);
        var transition = intent.transition();
        boolean changing = transition != null && !transition.key().equals(configurationTransition);
        String summary = changing ? summarizeForConfigurationTransition() : "";
        if (!executionConfiguration().equals(resolved)) configurationRevision++;
        request.configuration = resolved;
        if (changing) {
            var value = Nullness.requireNonNull(transition);
            restartAfterConfiguration(summary, value.prompt(), JsonValues.toMap(value.data()));
            configurationTransition = value.key();
        }
    }

    @NonNull String summarizeForConfigurationTransition() {
        try {
            return computeCompactionSummary(output.history());
        } catch (RuntimeException error) {
            log.warn(
                    "Agent {} configuration transition compaction failed; preserving original history",
                    agentId,
                    error);
            return "{}";
        }
    }

    void restartAfterConfiguration(
            @NonNull String summary,
            @NonNull String prompt,
            @NonNull Map<String, @Nullable Object> data) {
        if (!summary.isBlank() && !"{}".equals(summary)) {
            output.appendTurn(TurnRecord.rewind(output.nextTurn(), 0));
            models.appendAgentInit(models.linkCurrentSystemMessage());
            output.appendTurn(TurnRecord.compactionSummary(output.nextTurn(), summary));
        } else {
            models.rewindAndRestoreHistory();
        }
        output.appendTurn(PromptCompiler.sourcedUserPrompt(output.nextTurn(), prompt, data));
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

    private void retire(ExecutionControl.@NonNull CloseReason reason) {
        RequestHandle active;
        Locale closingLocale = locale();
        synchronized (this) {
            if (stopReason == null) stopReason = reason;
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
        notifyExecutionChanged();
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
