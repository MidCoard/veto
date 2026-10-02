package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.AgentLifecycle.BreakerTripException;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.AgentInbox;
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
 * Public agent facade and single-thread action-queue coordinator.
 *
 * <p>Supports concurrent request submission, cancellation and observation through the respective
 * entry points, but is not safe for arbitrary concurrent method calls. Exactly one thread may run
 * {@link #run()}; model/turn execution remains confined to that loop. Attach dependencies before
 * starting it. Lifecycle-monitor transitions, the concurrent action queue and independently guarded
 * history/waits coordinate external callers. Event and completion callbacks execute inline on their
 * producer's thread; no global callback serialization is provided.
 */
public final class AgentRunner implements Runnable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentRunner");
    private final @NonNull AgentLifecycle lifecycle;
    private final @NonNull AgentContinuationExecution continuations;
    private final @NonNull AgentToolExecution tools;
    private final @NonNull ModelSession models;
    private final @NonNull AgentOutput output;
    private final @NonNull String ownerAgentId;
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
        this.ownerAgentId = agentId;
        this.owner = owner;
        var actionQueue = new LinkedBlockingQueue<QueuedRequest>();
        output =
                new AgentOutput(
                        new AgentHistory(turnLogService, () -> sessionId, userId, agentId),
                        new AgentEvents(agentId, objectMapper, eventSink, () -> sessionId),
                        promptCompiler,
                        new ToolResultPresenter(objectMapper),
                        toolEngine,
                        this::outputView);
        var hooks =
                new AgentPluginHooks(
                        owner,
                        sessionId.toString(),
                        agentId,
                        () -> Thread.currentThread().isInterrupted(),
                        objectMapper,
                        caller,
                        () -> lifecycleOwner().sessionPlugins(),
                        () -> lifecycleOwner().eventManager(),
                        () -> lifecycleOwner().control().open(),
                        () -> {
                            var request = lifecycleOwner().control().request();
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
                        () -> lifecycleOwner().currentRequest().episode.breaker(),
                        () -> lifecycleOwner().modelConfiguration(),
                        () -> lifecycleOwner().checkExecutionBoundary(),
                        () -> lifecycleOwner().reserveRequestCall(),
                        () -> lifecycleOwner().tripBreaker());
        continuations =
                new AgentContinuationExecution(
                        agentId,
                        sessionId,
                        owner,
                        maxCallsPerEpisode,
                        output,
                        actionQueue,
                        () -> lifecycleOwner().sessionPlugins());
        lifecycle =
                new AgentLifecycle(
                        agentId,
                        persona,
                        toolEngine,
                        toolBoundary,
                        objectMapper,
                        binding,
                        owner,
                        sessionId,
                        output,
                        hooks,
                        models,
                        continuations,
                        actionQueue);
        tools =
                new AgentToolExecution(
                        toolEngine,
                        toolBoundary,
                        responses,
                        objectMapper,
                        configuredInterceptors,
                        output,
                        hooks,
                        lifecycle,
                        agentId,
                        userId,
                        owner,
                        sessionId);
    }

    // Suppliers are constructed early but invoked only after collaborator assembly.
    private @NonNull AgentLifecycle lifecycleOwner(@UnknownInitialization AgentRunner this) {
        return Nullness.requireNonNull(lifecycle, "Request lifecycle is not assembled");
    }

    private AgentOutput.@NonNull View outputView(@UnknownInitialization AgentRunner this) {
        return lifecycleOwner().outputView();
    }

    /** Attaches the vault that gates autonomous plugin work on the owner's unlocked credentials. */
    public void attachExecutionVault(@NonNull KeysteadVault vault) {
        continuations.attachExecutionVault(vault);
    }

    /** The reason execution is parked (approval, question, breaker, etc.), or {@code null}. */
    public String executionWaitReason() {
        return lifecycle.executionWaitReason();
    }

    void configureModelTiers(ModelTierRegistry registry) {
        lifecycle.configureModelTiers(registry);
    }

    /**
     * Cancels the task identified by its result future and waits for its execution to exit.
     *
     * @return whether the task settled within {@code timeout}; false if it is not owned by this
     *     runner or did not settle in time
     */
    public boolean cancelTask(
            @NonNull CompletableFuture<AgentResult> result, @NonNull Duration timeout)
            throws InterruptedException {
        return lifecycle.cancelTask(result, timeout);
    }

    /** The plugin provenance snapshot for the agent's latest model context. */
    public @NonNull PluginContextSnapshot pluginContext() {
        return lifecycle.pluginContext();
    }

    /** Sets how tool results are rendered to the model for subsequent turns. */
    public void setToolResultPresentation(
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        lifecycle.setToolResultPresentation(toolResultPresentation);
    }

    /** Attaches the durable store used to reload request continuations across restarts. */
    public void attachContinuationStore(@NonNull RequestContinuationStore store) {
        continuations.attachContinuationStore(store);
    }

    /** Attaches the plugin work source polled for autonomous observations. */
    public void attachWorkSource(@NonNull AgentInbox service) {
        continuations.attachWorkSource(service);
    }

    void onBackgroundRequest(@NonNull Consumer<RequestHandle> listener) {
        lifecycle.onBackgroundRequest(listener);
    }

    /** Signals that plugin work may be available, waking the loop to claim it. */
    public void signalWork() {
        lifecycle.signalWork();
    }

    /**
     * The agent's execution loop: drains the action queue on this (virtual) thread, running one
     * episode per dequeued request until the agent is terminated or the thread is interrupted.
     */
    public void run() {
        lifecycle.runningThread(Thread.currentThread());
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
            while (lifecycle.control().open()
                    && lifecycle.control().state() != AgentState.TERMINATED) {
                try {
                    QueuedRequest queued = lifecycle.take();
                    AgentAction action = queued.action();
                    if (queued.handle().cancelled) {
                        queued.handle().settled.complete(true);
                        continue;
                    }
                    if (action instanceof AgentAction.WorkAvailableAction) {
                        queued.handle().result.complete(AgentResult.success("", Map.of()));
                        queued.handle().settled.complete(true);
                        QueuedRequest work = lifecycle.claimWork();
                        if (work == null) continue;
                        queued = work;
                        action = work.action();
                    }
                    if (action instanceof AgentAction.TerminateAction) {
                        lifecycle.terminate();
                        queued.handle().result.complete(AgentResult.success("", Map.of()));
                        queued.handle().settled.complete(true);
                        break;
                    }
                    if (action instanceof AgentAction.ConfigurationAction) {
                        try {
                            lifecycle.refreshConfiguration();
                            queued.handle().result.complete(AgentResult.success("", Map.of()));
                        } catch (RuntimeException error) {
                            queued.handle()
                                    .result
                                    .complete(
                                            AgentResult.failure(
                                                    lifecycle.failureMessage(error), Map.of()));
                            log.warn("Agent configuration was not applied", error);
                        } finally {
                            queued.handle().settled.complete(true);
                        }
                        continue;
                    }
                    if (!lifecycle.beginRequest(queued)) continue;
                    if (action instanceof AgentAction.CompactAction) {
                        lifecycle.transitionTo(AgentState.RUNNING);
                        try {
                            lifecycle.checkExecutionBoundary();
                            lifecycle.processCompaction();
                            lifecycle.completeSuccess();
                        } catch (Exception e) {
                            log.error("Agent {} compaction failed", ownerAgentId, e);
                            lifecycle.completeFailure(lifecycle.failureMessage(e));
                        } finally {
                            // Clear a stale interrupt flag (see the UserPromptAction finally).
                            if (Thread.interrupted()) {
                                log.debug(
                                        "Agent {} cleared a stale interrupt after compaction",
                                        ownerAgentId);
                            }
                            queued.handle().settled.complete(true);
                            lifecycle.finishTurn(queued.handle(), true);
                            if (lifecycle.control().open()
                                    && !lifecycle.control().waiting(Wait.PLUGIN))
                                lifecycle.transitionTo(AgentState.IDLE);
                        }
                        continue;
                    }
                    if (action instanceof AgentAction.UserPromptAction
                            || action instanceof AgentAction.DirectUserPromptAction
                            || action instanceof AgentAction.WorkAction) {
                        String prompt =
                                action instanceof AgentAction.UserPromptAction upa
                                        ? upa.prompt()
                                        : action
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction direct
                                                ? direct.prompt()
                                                : "";
                        RequestHandle taskCancellation = queued.handle();
                        lifecycle.transitionTo(AgentState.RUNNING);
                        try {
                            lifecycle.checkTaskCancellation();
                            lifecycle.clearWait(Wait.PLUGIN);
                            lifecycle.checkExecutionBoundary();
                            lifecycle.refreshConfiguration();
                            if (action instanceof AgentAction.WorkAction) {
                                // Consume readiness before admitting any new model/tool effects.
                                lifecycle.currentRequest().awaiting();
                                models.refreshSystemHistory();

                                if (lifecycle.injectObservations()) runAutonomous(null);
                            } else {
                                var firstPrompt = lifecycle.processUserPrompt(prompt);
                                runAutonomous(firstPrompt);
                            }
                            lifecycle.checkTaskCancellation();
                            lifecycle.completeOrWaitForWork();
                        } catch (LinkageError error) {
                            lifecycle.completeFailure(lifecycle.failureMessage(error));
                            throw error;
                        } catch (BreakerTripException e) {
                            lifecycle.completeBreaker();
                        } catch (Exception e) {
                            if (taskCancellation.cancelled) {
                                synchronized (lifecycle) {
                                    // Wait for cancelTask to finish sending the one interrupt.
                                    AgentLifecycle.clearTaskInterrupt();
                                }
                                lifecycle.completeFailure(
                                        Msg.get(lifecycle.locale(), "error.agent.taskCancelled"),
                                        true,
                                        taskCancellation.requestId());
                            } else {
                                log.error("Agent {} task failed", ownerAgentId, e);
                                lifecycle.completeFailure(lifecycle.failureMessage(e));
                            }
                        } finally {
                            // A stray mid-round interrupt (external interference tripping the LLM
                            // HTTP call, a DB socket dying on interrupt) leaves the thread's
                            // interrupt flag SET. If it survives to the next actionQueue.take()
                            // the park throws instantly and the loop breaks - the agent looks
                            // crashed though nothing cancelled it. The round is already aborted
                            // (the cancel intent, if any, is honored), so clear the stale flag; a
                            // genuine cancel while PARKED still interrupts take() and breaks.
                            if (Thread.interrupted()) {
                                log.debug(
                                        "Agent {} cleared a stale interrupt after a prompt",
                                        ownerAgentId);
                            }
                            if (lifecycle.control().open()
                                    && !lifecycle.control().waiting(Wait.PLUGIN))
                                lifecycle.transitionTo(AgentState.IDLE);
                            lifecycle.finishTurn(taskCancellation, false);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (LinkageError error) {
            // A broken runtime cannot accept another episode. Release the current caller with a
            // failure before lifecycle cleanup, rather than leaving the UI waiting indefinitely.
            log.error("Agent {} runtime linkage failed", ownerAgentId, error);
            lifecycle.completeFailure(lifecycle.failureMessage(error));
        } finally {
            lifecycle.runningThread(null);
            try {
                lifecycle.terminate();
            } finally {
                try {
                    UserContext.clear();
                } finally {
                    lifecycle.notifyTermination();
                }
            }
        }
    }

    void runAutonomous(ModelSession.Prepared firstPrompt) {
        ToolCallContextHolder.ResponseDirective pending = null;
        String originatingCall = null;
        ModelExchange.Result previousExchange = null;
        while (lifecycle.control().state() == AgentState.RUNNING) {
            lifecycle.checkTaskCancellation();
            // Mid-episode task lifecycle: a background task that ended (or that the user
            // stopped) during THIS episode is reported at the next iteration, not only at the
            // start of the next episode. Cheap no-op when the queue is empty.
            // processUserPrompt already drained notices before preparing the first immutable
            // request. Do not mutate history between that compilation and its dispatch.
            if (firstPrompt == null) {
                lifecycle.injectObservations();
            }
            if (pending == null && lifecycle.currentRequest().episode.breaker().shouldTrip()) {
                lifecycle.tripBreaker();
                throw new BreakerTripException();
            }
            var accepted = pending;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Push pushed) {
                pending = null;
                lifecycle.checkTaskCancellation();
                flowStack.push(pushed.flow());
            }
            if (!flowStack.defaultSelected()) {
                ModelFlow flow = flowStack.top();
                long configuration = lifecycle.configurationRevision();
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
                if (lifecycle.configurationRevision() != configuration) continue;
                if (control.finish) return;
                if (!flowStack.defaultSelected() && flowStack.top() == flow) return;
                continue;
            }
            ModelExchange.Result exchange;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer) {
                if (!answer.publish()) {
                    String result = answer.response().message();
                    lifecycle.currentRequest().message = result == null ? "" : result;
                    return;
                }
                exchange =
                        new ModelExchange.Result(
                                Nullness.requireNonNull(previousExchange).request(),
                                answer.response(),
                                answer.citations(),
                                originatingCall,
                                true);
            } else {
                exchange = models.callModel(firstPrompt);
                firstPrompt = null;
            }
            previousExchange = exchange;
            VetoResponse response = exchange.response();
            originatingCall = exchange.modelCallId();
            lifecycle.checkTaskCancellation();

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
                                lifecycle.toolInvocation());
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
        long configuration = lifecycle.configurationRevision();
        return new ModelFlow.Runtime() {
            @Override
            public @NonNull String input() {
                sources.check();
                return lifecycle.currentRequest().episode.task();
            }

            @Override
            public void push(@NonNull ModelFlow child) {
                sources.check();
                lifecycle.checkTaskCancellation();
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
                lifecycle.checkTaskCancellation();
                lifecycle.injectObservations();
            }

            @Override
            public boolean running() {
                return sources.active()
                        && lifecycle.control().state() == AgentState.RUNNING
                        && lifecycle.configurationRevision() == configuration;
            }

            @Override
            public @NonNull ToolResult tool(
                    @NonNull ToolCall call, @NonNull ActionContext context) {
                sources.check();
                return tools.executeOneCall(
                        call, new ToolBatch(null, false, context), lifecycle.toolInvocation());
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
        if (lifecycle.currentRequest().episode.breaker().shouldTrip()) {
            lifecycle.tripBreaker();
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
                            lifecycle.toolInvocation());
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer)
                return new ModelExchange.Result(
                        exchange.request(),
                        answer.response(),
                        answer.citations(),
                        exchange.modelCallId(),
                        true);
            lifecycle.checkTaskCancellation();
        }
    }

    /**
     * Seeds replayed history before the first episode (idempotent). If the replay shows an episode
     * left unfinished, the agent is parked in the {@code INTERRUPTED} wait so the next prompt
     * records an explicit continuation boundary.
     */
    public void seedHistory(@NonNull List<TurnRecord> replayed) {
        if (output.seedHistory(replayed)) lifecycle.saveExecutionWait(Wait.INTERRUPTED);
    }

    /** Whether the agent is running or has queued work still to process. */
    public boolean hasPendingWork() {
        return lifecycle.hasPendingWork();
    }

    /** Enqueues a new episode for {@code action} and returns the handle that owns its result. */
    public @NonNull RequestHandle startTask(
            Consumer<AgentResult> callback, @NonNull AgentAction action) {
        return lifecycle.startTask(callback, action);
    }

    /** Enqueues an action without retaining a result handle (fire-and-forget). */
    public void enqueue(@NonNull AgentAction action) {
        lifecycle.enqueue(action);
    }

    /** Replaces the base model binding used for subsequent configuration resolution. */
    public void bind(@NonNull LlmBinding binding) {
        lifecycle.bind(binding);
    }

    /** The model binding currently in effect. */
    public @NonNull LlmBinding binding() {
        return lifecycle.binding();
    }

    /** Subscribes a listener for user-facing messages emitted by the agent. */
    public void addMessageListener(@NonNull Consumer<String> listener) {
        output.addMessageListener(listener);
    }

    /** Unsubscribes a user-facing-message listener. */
    public void removeMessageListener(@NonNull Consumer<String> listener) {
        output.removeMessageListener(listener);
    }

    /** Subscribes a listener for the agent's interim reasoning thoughts. */
    public void addThoughtListener(@NonNull Consumer<String> listener) {
        output.addThoughtListener(listener);
    }

    /** Unsubscribes an interim-thought listener. */
    public void removeThoughtListener(@NonNull Consumer<String> listener) {
        output.removeThoughtListener(listener);
    }

    /** Subscribes a listener notified when a tool call parks for HITL approval. */
    public void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        output.addVetoListener(listener);
    }

    /** Unsubscribes a HITL-veto listener. */
    public void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        output.removeVetoListener(listener);
    }

    /** Subscribes a listener notified as each tool call turn is appended. */
    public void addToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        output.addToolCallListener(listener);
    }

    /** Unsubscribes a tool-call listener. */
    public void removeToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        output.removeToolCallListener(listener);
    }

    /** Subscribes a listener notified as each tool result turn is appended. */
    public void addToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        output.addToolResultListener(listener);
    }

    /** Unsubscribes a tool-result listener. */
    public void removeToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        output.removeToolResultListener(listener);
    }

    /** The agent's current lifecycle state. */
    public @NonNull AgentState state() {
        return lifecycle.state();
    }

    /** The durable turn history, oldest first. */
    public @NonNull List<TurnRecord> history() {
        return output.history();
    }

    /** The read-history used for drift detection. */
    public @NonNull ReadHistory readHistory() {
        return lifecycle.readHistory();
    }

    /** The tool names this agent is authorized to call. */
    public @NonNull Set<String> whitelistedToolsView() {
        return lifecycle.whitelistedToolsView();
    }

    /** Sets the terminal policy and host effect guard applied to each episode. */
    public void setExecutionPolicy(@NonNull AgentExecutionPolicy policy) {
        lifecycle.setExecutionPolicy(policy);
    }

    /** Whether the current episode has consumed its model-call budget. */
    public boolean budgetExhausted() {
        RequestHandle request = lifecycle.control().request();
        return request != null && request.episode.breaker().shouldTrip();
    }

    /** The agent's stable identity (its persona id). */
    public @NonNull String agentId() {
        return lifecycle.agentId();
    }

    /** Enqueues a re-resolution of the agent's plugin-supplied configuration. */
    public void refreshConfiguration() {
        lifecycle.enqueue(new AgentAction.ConfigurationAction());
    }

    /** Sets the locale used to render agent-thread messages (null resets to English). */
    public void setLocale(Locale locale) {
        lifecycle.setLocale(locale);
    }

    /** The locale currently used for agent-thread messages. */
    public @NonNull Locale locale() {
        return lifecycle.locale();
    }

    /** The persona currently in effect. */
    public @NonNull AgentPersona personaView() {
        return lifecycle.personaView();
    }

    /** Attaches the session's resolved plugin selection. */
    public void attachSessionPlugins(@NonNull SessionPlugins value) {
        lifecycle.attachSessionPlugins(value);
    }

    /**
     * Applies a new persona, re-scoping the tool whitelist and bumping the configuration revision.
     */
    public void applyPersona(@NonNull AgentPersona persona) {
        lifecycle.applyPersona(persona);
    }

    void onTermination(@NonNull Runnable callback) {
        lifecycle.onTermination(callback);
    }

    /** The session this runner belongs to. */
    public @NonNull UUID sessionId() {
        return lifecycle.sessionId();
    }

    /** Closes the runner for host shutdown, interrupting any in-flight episode. */
    public void shutdown() {
        lifecycle.close(ExecutionControl.CloseReason.SHUTDOWN);
    }

    /** Requests termination of the agent (agent deleted); the loop exits at its next boundary. */
    public void terminate() {
        lifecycle.terminate();
    }

    /** Attaches the host dispatcher used by agent workflow and lifecycle event producers. */
    public void attachEventManager(@NonNull EventManager events) {
        lifecycle.attachEventManager(events);
    }
}
