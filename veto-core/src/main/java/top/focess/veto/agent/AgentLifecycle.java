package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
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
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.event.EventManager;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/**
 * Owns request completion, cancellation, compaction and persona transitions.
 *
 * <p>Not independently thread-safe. Model/compaction operations belong to the single runner loop;
 * externally callable admission, cancellation and termination paths coordinate on the shared
 * lifecycle monitor. Some event, inbox and future callbacks execute inline under that monitor, so
 * callbacks must not wait for work that needs it. Termination notification snapshots its callback
 * under the monitor and normally invokes it outside; late registration can invoke it inline under
 * the monitor.
 */
final class AgentLifecycle {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentLifecycle");
    private final @NonNull String agentId;
    private final @NonNull UUID sessionId;
    private final String owner;
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull ToolExecutionBoundary toolBoundary;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull ReadHistory readHistory;
    private final @NonNull AgentPersona basePersona;
    private volatile @NonNull AgentPersona persona;
    private volatile @NonNull Set<String> whitelistedTools;
    private volatile @NonNull LlmBinding binding;
    private volatile @NonNull LlmBinding baseBinding;
    private volatile @NonNull Locale locale = Locale.ENGLISH;
    private volatile AgentProfile.Prompt prompt;
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

    private final @NonNull AgentOutput output;
    private final @NonNull AgentPluginHooks hooks;
    private final @NonNull ModelSession models;
    private final @NonNull AgentContinuationExecution continuations;
    private volatile @NonNull ExecutionControl control = new ExecutionControl.Idle();
    private final @NonNull BlockingQueue<QueuedRequest> actionQueue;
    private final @NonNull List<QueuedRequest> deferredUserPrompts = new ArrayList<>();
    private final @NonNull AtomicBoolean workQueued = new AtomicBoolean();
    private volatile Thread runningThread;
    private Consumer<RequestHandle> backgroundRequestListener;
    private Runnable terminationCallback;
    private boolean terminationNotified;
    private EventManager eventManager;

    AgentLifecycle(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine toolEngine,
            @NonNull ToolExecutionBoundary toolBoundary,
            @NonNull ObjectMapper objectMapper,
            @NonNull LlmBinding binding,
            String owner,
            @NonNull UUID sessionId,
            @NonNull AgentOutput output,
            @NonNull AgentPluginHooks hooks,
            @NonNull ModelSession models,
            @NonNull AgentContinuationExecution continuations,
            @NonNull BlockingQueue<QueuedRequest> actionQueue) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.owner = owner;
        this.toolEngine = toolEngine;
        this.toolBoundary = toolBoundary;
        this.objectMapper = objectMapper;
        this.readHistory = toolBoundary.readHistory();
        this.basePersona = persona;
        this.persona = persona;
        this.binding = binding;
        this.baseBinding = binding;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        this.output = output;
        this.hooks = hooks;
        this.models = models;
        this.continuations = continuations;
        this.actionQueue = actionQueue;
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
                binding.options().contextWindowOrDefault());
    }

    ModelSession.@NonNull Configuration modelConfiguration() {
        return new ModelSession.Configuration(
                persona,
                binding,
                prompt,
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
                whitelistedTools,
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
            saveExecutionWait(Wait.PLUGIN);
            transitionTo(AgentState.WAITING);
            signalWork();
        } else completeSuccess();
    }

    // Readiness callbacks can run under a RequestHandle monitor; never acquire this monitor here.
    void signalWork() {
        if (control.open() && workQueued.compareAndSet(false, true))
            actionQueue.add(
                    new QueuedRequest(
                            new AgentAction.WorkAvailableAction(), new RequestHandle(this)));
    }

    synchronized QueuedRequest claimWork() {
        workQueued.set(false);
        var previous = control.request();
        var queued = continuations.claimWork(control, this);
        if (queued == null) return null;
        control = control.withRequest(queued.handle());
        if (queued.handle() != previous && backgroundRequestListener != null)
            backgroundRequestListener.accept(queued.handle());
        return queued;
    }

    @NonNull QueuedRequest take() throws InterruptedException {
        return actionQueue.take();
    }

    synchronized boolean beginRequest(@NonNull QueuedRequest queued) {
        if (!control.open()) {
            queued.handle().result.complete(AgentResult.failure("Agent terminated", Map.of()));
            queued.handle().settled.complete(true);
            return false;
        }
        if (control.waiting(Wait.PLUGIN) && !(queued.action() instanceof AgentAction.WorkAction)) {
            deferredUserPrompts.add(queued);
            signalWork();
            return false;
        }
        control = control.withRequest(queued.handle());
        return true;
    }

    synchronized void finishTurn(@NonNull RequestHandle request, boolean compact) {
        boolean parked = !compact && control.request() == request && control.waiting(Wait.PLUGIN);
        if (control.request() == request
                && (compact || !control.waiting(Wait.PLUGIN) && !control.waiting(Wait.BREAKER)))
            control = control.withRequest(null);
        // Plugin work resumes this same handle, so a parked turn is not execution settlement.
        if (!parked) request.settled.complete(true);
        clearTaskInterrupt();
    }

    synchronized void runningThread(Thread thread) {
        runningThread = thread;
    }

    synchronized void onBackgroundRequest(@NonNull Consumer<RequestHandle> listener) {
        backgroundRequestListener = listener;
    }

    void saveExecutionWait(Wait reason) {
        synchronized (this) {
            if (!control.open()) return;
            var waits =
                    control instanceof ExecutionControl.Suspended suspended
                            ? new HashSet<>(suspended.waits())
                            : new HashSet<Wait>();
            var activity =
                    control instanceof ExecutionControl.Executing executing
                            ? executing.activity()
                            : control instanceof ExecutionControl.Suspended suspended
                                    ? suspended.activity()
                                    : ExecutionControl.Activity.MODEL;
            if (reason == null) {
                waits.removeAll(
                        Set.of(Wait.APPROVAL, Wait.QUESTION, Wait.BREAKER, Wait.INTERRUPTED));
            } else waits.add(reason);
            control =
                    waits.isEmpty()
                            ? new ExecutionControl.Executing(control.request(), activity)
                            : new ExecutionControl.Suspended(control.request(), activity, waits);
        }
        notifyExecutionChanged();
    }

    void clearWait(@NonNull Wait reason) {
        synchronized (this) {
            if (!(control instanceof ExecutionControl.Suspended suspended)) return;
            var waits = new HashSet<>(suspended.waits());
            waits.remove(reason);
            control =
                    waits.isEmpty()
                            ? new ExecutionControl.Executing(
                                    suspended.request(), suspended.activity())
                            : new ExecutionControl.Suspended(
                                    suspended.request(), suspended.activity(), waits);
        }
    }

    String executionWaitReason() {
        for (Wait reason : List.of(Wait.APPROVAL, Wait.QUESTION, Wait.BREAKER))
            if (control.waiting(reason)) return reason.name();
        return null;
    }

    void checkExecutionBoundary() {
        executionPolicy.check().run();
        if (!control.open()) throw new CancellationException("Agent terminated");
        checkTaskCancellation();
        RequestHandle request = control.request();
        if (request != null) request.awaiting();
    }

    void configureModelTiers(ModelTierRegistry registry) {
        modelTierRegistry = registry;
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
                AgentLifecycle.clearTaskInterrupt();
                throw new CancellationException("Task cancelled");
            }
        }
    }

    boolean cancelTask(@NonNull CompletableFuture<AgentResult> result, @NonNull Duration timeout)
            throws InterruptedException {
        RequestHandle task;
        synchronized (this) {
            if (!(result instanceof RequestHandle.Result owned) || owned.handle().owner != this)
                return false;
            task = owned.handle();
            if (!result.isDone()) task.cancelled = true;
            if (task.cancelled && task != control.request()) {
                actionQueue.removeIf(queued -> queued.handle() == task);
                deferredUserPrompts.removeIf(queued -> queued.handle() == task);
                task.result.complete(AgentResult.failure("Task cancelled", Map.of()));
                task.settled.complete(true);
            }
            if (task.cancelled && task == control.request() && control.waiting(Wait.PLUGIN)) {
                completeFailure("Task cancelled", true, task.requestId());
                control = control.withRequest(null);
                task.settled.complete(true);
                transitionTo(AgentState.IDLE);
            }
            if (task.cancelled && task == control.request() && !task.interruptSent) {
                task.interruptSent = true;
                toolBoundary.declineAll();
                Thread thread = runningThread;
                if (thread != null) thread.interrupt();
            }
        }
        try {
            return task.settled.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @NonNull PluginContextSnapshot pluginContext() {
        return models.pluginContext();
    }

    void setToolResultPresentation(@NonNull ToolResultPresentationMode toolResultPresentation) {
        this.toolResultPresentation = toolResultPresentation;
    }

    ModelSession.@NonNull Prepared processUserPrompt(@NonNull String prompt) {
        continuations.remember(currentRequest().episode);
        prompt = hooks.captureUserPrompt(hooks.beforeInput(prompt));
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
        saveExecutionWait(null);
        injectObservations();

        models.refreshSystemHistory();
        TurnRecord prospectiveUserTurn =
                resumeContext != null
                        ? TurnRecord.breakerContinuation(
                                output.turnNumber() + 1, prompt, resumeContext)
                        : TurnRecord.userPrompt(output.turnNumber() + 1, prompt);
        List<TurnRecord> prospectiveHistory;
        synchronized (this) {
            prospectiveHistory = new ArrayList<>(output.history());
        }
        prospectiveUserTurn = withRequestId(prospectiveUserTurn);
        prospectiveHistory.add(prospectiveUserTurn);
        var firstPrompt = models.preparePrompt(prospectiveHistory, false);
        output.appendTurn(
                withRequestId(
                        resumeContext != null
                                ? TurnRecord.breakerContinuation(
                                        output.nextTurn(), prompt, resumeContext)
                                : TurnRecord.userPrompt(output.nextTurn(), prompt)));
        RequestHandle cancellation = control.request();
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
            output.emitMessage(Msg.get(locale, "error.agent.compactNothing"));
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
        output.emitMessage(Msg.get(locale, "error.agent.compactDone", workTurns.size()));
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
            log.debug("Cleared task interrupt before lifecycle cleanup");
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
        saveExecutionWait(Wait.BREAKER);
        String notice = LoopBreaker.tripNotice(locale);
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
                return Msg.get(locale, "error.agent.vaultLocked");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof VetoRefusedException refused) {
                return Msg.get(
                        locale,
                        refused.approvalRequested
                                ? "error.agent.approvalNotGranted"
                                : "error.agent.vetoRefused");
            }
            if (t instanceof CredentialException) {
                return Msg.get(locale, "error.agent.credentialMissing");
            }
            if (t instanceof LlmTimeoutException) {
                return Msg.get(locale, "error.agent.llmTimeout");
            }
            if (t instanceof LlmRateLimitException) {
                return Msg.get(locale, "error.agent.llmRateLimit");
            }
            if (t instanceof LlmAuthException) {
                return Msg.get(locale, "error.agent.llmAuth");
            }
            if (t instanceof ModelSchemaException) {
                return Msg.get(locale, "error.agent.llmSchema", String.valueOf(t.getMessage()));
            }
            if (t instanceof ModelCapabilityException mce) {
                // The same type covers transport call failures and unparseable responses
                // (AbstractLlmProvider); discriminate on the fixed message prefix.
                String detail = String.valueOf(mce.getMessage());
                if (detail.contains("could not be parsed")) {
                    return Msg.get(locale, "error.agent.llmParse");
                }
                return Msg.get(locale, "error.agent.llmCallFailed", detail);
            }
            if (t instanceof IllegalStateException
                    && String.valueOf(t.getMessage()).contains("embed")) {
                // ProviderEmbedder failures surface as IllegalStateException (best-effort memory).
                return Msg.get(locale, "error.agent.embedFailed", String.valueOf(t.getMessage()));
            }
        }
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return Msg.get(locale, "error.agent.taskFailed", detail);
    }

    void complete(@NonNull AgentResult result) {
        RequestHandle task;
        synchronized (this) {
            task = control.request();
            if (task != null && task.cancelled)
                result = AgentResult.failure("Task cancelled", Map.of());
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
        }
        if (task != null) {
            continuations.settled(task.episode);
            task.releaseWaits();
            task.result.complete(result);
        }
    }

    void transitionTo(@NonNull AgentState next) {
        synchronized (this) {
            if (!control.open()) return;
            if (next == AgentState.INTERCEPTED) {
                saveExecutionWait(Wait.APPROVAL);
                return;
            }
            if (next == AgentState.PAUSED) {
                saveExecutionWait(Wait.PAUSE);
                return;
            }
            if (next == AgentState.TERMINATED) {
                control = new ExecutionControl.Closed(ExecutionControl.CloseReason.AGENT_DELETED);
            } else if (!(control instanceof ExecutionControl.Suspended)) {
                control =
                        next == AgentState.IDLE && control.request() == null
                                ? new ExecutionControl.Idle()
                                : new ExecutionControl.Executing(
                                        control.request(),
                                        next == AgentState.WAITING
                                                ? ExecutionControl.Activity.TOOL
                                                : ExecutionControl.Activity.MODEL);
            }
        }
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

    boolean hasPendingWork() {
        if (!control.open() || control.state() == AgentState.TERMINATED) return false;
        return control.state() != AgentState.IDLE
                || actionQueue.stream()
                        .anyMatch(
                                action ->
                                        action.action() instanceof AgentAction.UserPromptAction
                                                || action.action()
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction
                                                || action.action()
                                                        instanceof AgentAction.CompactAction);
    }

    @NonNull RequestHandle startTask(Consumer<AgentResult> callback, @NonNull AgentAction action) {
        synchronized (this) {
            if (!control.open()) throw new IllegalStateException("Agent has terminated");
            if (action instanceof AgentAction.UserPromptAction prompt)
                action = new AgentAction.UserPromptAction(hooks.captureUserPrompt(prompt.prompt()));
            if (action instanceof AgentAction.DirectUserPromptAction prompt)
                action =
                        new AgentAction.DirectUserPromptAction(
                                hooks.captureUserPrompt(prompt.prompt()));
            RequestHandle previous = control.request();
            String promptText =
                    action instanceof AgentAction.UserPromptAction prompt
                            ? prompt.prompt()
                            : action instanceof AgentAction.DirectUserPromptAction direct
                                    ? direct.prompt()
                                    : null;
            boolean continuation =
                    control.waiting(Wait.BREAKER)
                            && promptText != null
                            && "continue".equalsIgnoreCase(promptText.strip());
            RequestHandle handle =
                    continuation && previous != null
                            ? new RequestHandle(this, previous.episode)
                            : new RequestHandle(this, continuations.newEpisode());
            if (callback != null) handle.result.thenAccept(callback);
            actionQueue.add(new QueuedRequest(action, handle));
            notifyExecutionChanged();
            return handle;
        }
    }

    void enqueue(@NonNull AgentAction action) {
        if (action instanceof AgentAction.TerminateAction && !control.open()) return;
        startTask(null, action);
    }

    void bind(@NonNull LlmBinding binding) {
        baseBinding = binding;
    }

    @NonNull LlmBinding binding() {
        return binding;
    }

    @NonNull AgentState state() {
        return control.state();
    }

    @NonNull ReadHistory readHistory() {
        return readHistory;
    }

    @NonNull Set<String> whitelistedToolsView() {
        return whitelistedTools;
    }

    void setExecutionPolicy(@NonNull AgentExecutionPolicy policy) {
        var terminal = policy.terminal();
        if (terminal != null && !whitelistedTools.contains(terminal.tool()))
            throw new IllegalArgumentException("Terminal tool must be in the agent's whitelist");
        executionPolicy = policy;
    }

    @NonNull String agentId() {
        return agentId;
    }

    void setLocale(Locale locale) {
        this.locale = locale != null ? locale : Locale.ENGLISH;
        toolBoundary.locale(this.locale);
    }

    @NonNull Locale locale() {
        return locale;
    }

    @NonNull AgentPersona personaView() {
        return persona;
    }

    void attachSessionPlugins(@NonNull SessionPlugins value) {
        sessionPlugins = value;
    }

    void applyPersona(@NonNull AgentPersona persona) {
        var selection = sessionPlugins;
        if (selection != null)
            persona =
                    persona.withWhitelistedTools(
                            selection.tools(sessionId.toString(), persona.whitelistedTools()));
        if (this.persona.equals(persona)) return;
        this.persona = persona;
        configurationRevision++;
        whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        notifyExecutionChanged();
    }

    void refreshConfiguration() {
        var selection = sessionPlugins;
        String currentOwner = owner;
        if (selection == null || currentOwner == null) {
            binding = baseBinding;
            return;
        }
        var available =
                selection.tools(sessionId.toString(), Set.copyOf(toolEngine.getActiveTools(null)));
        var original = basePersona;
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
            applyPersona(original);
            binding = baseBinding;
            prompt = null;
            return;
        }
        var profile = intent.profile();
        var tiers = modelTierRegistry;
        if (tiers == null) throw new IllegalStateException("Model tiers unavailable");
        var resolved =
                AgentProfiles.resolve(
                        agentId, currentOwner, profile, available, baseBinding, tiers);
        var transition = intent.transition();
        boolean changing = transition != null && !transition.key().equals(configurationTransition);
        String summary = changing ? summarizeForRoleChange() : "";
        var next = resolved.persona();
        applyPersona(
                new AgentPersona(
                        next.id(),
                        next.name(),
                        next.description(),
                        next.whitelistedTools(),
                        next.role(),
                        original.configurationOwner()));
        if (!binding.equals(resolved.binding()) || !Objects.equals(prompt, resolved.prompt()))
            configurationRevision++;
        binding = resolved.binding();
        prompt = resolved.prompt();
        if (changing) {
            var value = Nullness.requireNonNull(transition);
            restartAfterConfiguration(summary, value.prompt(), JsonValues.toMap(value.data()));
            configurationTransition = value.key();
        }
    }

    @NonNull String summarizeForRoleChange() {
        try {
            return computeCompactionSummary(output.history());
        } catch (RuntimeException error) {
            log.warn(
                    "Agent {} role-change compaction failed; preserving original history",
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

    void onTermination(@NonNull Runnable callback) {
        synchronized (this) {
            if (terminationNotified) {
                callback.run();
                return;
            }
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
        }
    }

    @NonNull UUID sessionId() {
        return sessionId;
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

    void terminate() {
        close(ExecutionControl.CloseReason.AGENT_DELETED);
    }

    void close(ExecutionControl.@NonNull CloseReason reason) {
        synchronized (this) {
            if (!control.open()) return;
            AgentResult interrupted =
                    AgentResult.failure(Msg.get(locale, "error.agent.interrupted"), Map.of());
            RequestHandle active = control.request();
            if (active != null) {
                active.cancelled = true;
                active.releaseWaits();
                active.result.complete(interrupted);
                if (control.waiting(Wait.PLUGIN)) active.settled.complete(true);
            }
            control = new ExecutionControl.Closed(reason);
            List<QueuedRequest> queued = new ArrayList<>(deferredUserPrompts);
            deferredUserPrompts.clear();
            actionQueue.drainTo(queued);
            for (QueuedRequest request : queued) {
                request.handle().result.complete(interrupted);
                request.handle().settled.complete(true);
            }
        }
        notifyExecutionChanged();
        toolBoundary.clear();
        Thread thread = runningThread;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    void attachEventManager(@NonNull EventManager events) {
        eventManager = events;
    }
}
