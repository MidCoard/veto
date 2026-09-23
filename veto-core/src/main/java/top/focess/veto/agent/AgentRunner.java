package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.GuidedStepContext;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.RefusalObservation;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.ToolExecutionPermit.TaskBinding;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.GenerateAction;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.loop.ResponseRequest;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ResponseSubmission;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.CredentialException;
import top.focess.veto.api.llm.exceptions.LlmAuthException;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.LlmRateLimitException;
import top.focess.veto.api.llm.exceptions.LlmTimeoutException;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.monitor.RequestContinuationStore;
import top.focess.veto.plugin.runtime.PluginJson;
import top.focess.veto.plugin.runtime.PluginLifecycleEvents;
import top.focess.veto.plugin.runtime.SessionPlugins;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/**
 * The execution engine — the ReAct loop running in one of two modes (guided or autonomous) on the
 * agent's virtual thread. Owned by {@link VetoAgent}; never exposed to workflows/transports. The
 * code is synchronous-style while physically non-blocking on Java 21 virtual threads.
 *
 * <p>Autonomous: think → act → observe → assess, full reasoning each step. Guided: drives a typed
 * actions program (IR) — {@code tool} actions may skip the model call; {@code generate} is the only
 * model-invoking action; {@code goto}/{@code conditional_goto}/{@code STOP} are zero-call.
 */
public class AgentRunner {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentRunner");

    // --- identity / deps ---
    private final @NonNull String agentId;
    // The persona + its tool-name view are mutable: the delegation transform re-scopes them from
    // STANDALONE to LEADER (and back on disband) in place. Volatile - written once per transform on
    // the loop thread, read on the same thread each compile; the volatile keeps the view consistent
    // for inspection from other threads.
    private volatile @NonNull AgentPersona persona;
    private volatile @NonNull Set<String> whitelistedTools;
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull ResponseValidator responses;
    private final @NonNull Gateway gateway;
    private final @NonNull HitlRegistry hitlRegistry;
    private final @NonNull IngressDefense ingressDefense;
    private final @NonNull List<LoopInterceptor> interceptors;
    private final @NonNull PromptCompiler promptCompiler;
    private @NonNull ToolResultPresentationMode toolResultPresentation =
            ToolResultPresentationMode.BASIC;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull ToolResultPresenter toolResultPresenter;
    private final @NonNull LoopBreaker breaker;
    private final @NonNull ReadHistory readHistory;
    // When configured, loop emissions are published as per-session DeltaFrames for streaming.
    private final @NonNull AgentEvents events;
    // The session this agent's turns belong to. Defaults to the agent's own id (a UUID) at
    // construction; the DB-backed create path overrides it with the real session id so the
    // turn_records.session_id column groups a session's 1+N agent streams correctly. Volatile: set
    // once at creation before the loop processes any turn.
    private volatile @NonNull UUID sessionId;
    // When configured, every in-memory turn is also persisted for audit and replay.
    private final @NonNull AgentHistory journal;
    private final @NonNull UUID userId;
    private final BackgroundTaskManager backgroundTaskManager;
    // The session owner (username) whose model-tier profile resolves this agent's tier. Threaded
    // into each tool's ToolCallContext so group-spawned Mates / Leaders resolve against the user's
    // active profile (per-user model-tier configuration). It remains unset until session
    // activation. Volatile: set once before the loop processes any tool call.
    private volatile String owner;
    // The session's message locale for user-facing strings emitted on the agent's virtual thread
    // (breaker notices, compaction summaries, failure reasons). Stamped by AgentService.submit
    // from the REST request's Accept-Language; English default covers the terminal/IPC path.
    // Volatile: written on the transport thread, read on the agent's virtual thread.
    private volatile @NonNull Locale locale = Locale.ENGLISH;
    // The group this agent belongs to (the group it leads, or the group it is a Mate of); null for
    // a single-agent (STANDALONE) loop. Stamped by group-spawning code and threaded into each
    // tool's ToolCallContext so group-scoped tools (create_node, post_message, ...) resolve the
    // caller's group without a groupId argument.
    private volatile UUID groupId;

    // --- model binding (provider/model/credential), set per prompt ---
    private volatile @NonNull LlmBinding binding;

    // The pre-transform STANDALONE persona + binding, stashed when the agent transforms into a
    // Leader so disband_group can reverse the transform and restore them. Null when not leading.
    private volatile AgentPersona preTransformPersona;
    private volatile LlmBinding preTransformBinding;

    // --- loop state (mutated only by the runner's virtual thread) ---
    private final @NonNull BlockingQueue<AgentAction> actionQueue = new LinkedBlockingQueue<>();
    private final @NonNull List<AgentAction.@NonNull DirectUserPromptAction> deferredUserPrompts =
            new ArrayList<>();
    private volatile @NonNull AgentState state = AgentState.IDLE;
    private KeysteadVault monitorVault;

    public void attachMonitorVault(@NonNull KeysteadVault vault) {
        monitorVault = vault;
    }

    private enum WaitReason {
        APPROVAL,
        QUESTION,
        BREAKER
    }

    private volatile WaitReason executionWait;
    private volatile boolean recoveredWait;

    private void saveExecutionWait(WaitReason reason) {
        executionWait = reason;
        if (reason == null) recoveredWait = false;
        notifyExecutionChanged();
    }

    public String executionWaitReason() {
        WaitReason reason = executionWait;
        return reason == null ? null : reason.name();
    }

    private void checkExecutionBoundary() {
        if (!sessionAlive) throw new CancellationException("Agent terminated");
        checkTaskCancellation();
    }

    private int turnNumber = 0;
    private final @NonNull GuidedProgram program;
    private ModelTierRegistry guidedTierRegistry;

    void configureGuided(ModelTierRegistry registry, int maxSteps) {
        program.configure(maxSteps);
        this.guidedTierRegistry = registry;
    }

    private volatile @NonNull CompletableFuture<AgentResult> resultFuture =
            new CompletableFuture<>();
    private Consumer<AgentResult> callback;
    private final @NonNull Map<AgentAction, TaskCancellation> taskActions = new IdentityHashMap<>();
    private final @NonNull Map<CompletableFuture<AgentResult>, TaskCancellation> cancellableTasks =
            new HashMap<>();
    private volatile TaskCancellation activeCancellation;
    private CompletableFuture<AgentResult> lastExitedTask;

    private static final class TaskCancellation {
        final @NonNull CompletableFuture<AgentResult> result;
        final @NonNull CompletableFuture<Boolean> exited = new CompletableFuture<>();
        final Consumer<AgentResult> callback;
        volatile boolean cancelled;
        boolean interruptSent;
        String requestId;

        TaskCancellation(
                @NonNull CompletableFuture<AgentResult> result, Consumer<AgentResult> callback) {
            this.result = result;
            this.callback = callback;
        }
    }

    private synchronized void checkTaskCancellation() {
        TaskCancellation task = activeCancellation;
        if (task != null && task.cancelled) {
            // Cancellation remains recorded on the task; cleanup must not inherit the signal
            // and close database sockets while persisting the cancelled outcome.
            clearTaskInterrupt();
            throw new CancellationException("Task cancelled");
        }
    }

    public boolean cancelTask(
            @NonNull CompletableFuture<AgentResult> result, @NonNull Duration timeout)
            throws InterruptedException {
        TaskCancellation task;
        synchronized (this) {
            task = cancellableTasks.get(result);
            if (task == null) return result == lastExitedTask;
            if (!result.isDone()) task.cancelled = true;
            if (task.cancelled && task == activeCancellation && !task.interruptSent) {
                task.interruptSent = true;
                hitlRegistry.declineAll(agentId);
                Thread thread = runningThread;
                if (thread != null) thread.interrupt();
            }
        }
        try {
            return task.exited.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private volatile boolean sessionAlive = true;
    private double correctionFactor = 1.0;
    private volatile PluginContextSnapshot lastPluginContext;

    public @NonNull PluginContextSnapshot pluginContext() {
        var snapshot = lastPluginContext;
        return snapshot == null
                ? PluginContextSnapshot.from(persona.whitelistedTools(), false)
                : snapshot;
    }

    // Set only when the model-call ceiling trips. The next exact "continue" prompt consumes it and
    // carries the prior task into a self-contained resume turn; any other prompt starts a new task.
    private boolean awaitingBreakerContinuation = false;

    // The episode's first request is compiled against a prospective history containing the new
    // user turn. That exact immutable payload is dispatched after AGENT_INIT → USER_PROMPT are
    // persisted in logical order. Null after the first dispatch.
    private CompiledPrompt preparedFirstPrompt = null;
    private @NonNull String activeUserTask = "";
    // Exact tool+args calls declined with DECLINE_AND_CONTINUE in this user-prompt episode. A model
    // retry is answered locally instead of bothering the user with the same approval again.
    private final @NonNull Set<String> declinedCallSignatures = new HashSet<>();

    public AgentRunner(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine toolEngine,
            @NonNull Gateway gateway,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull IngressDefense ingressDefense,
            List<LoopInterceptor> interceptors,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper objectMapper,
            long maxCallsPerEpisode,
            @NonNull LlmBinding binding,
            DeltaBroker deltaBroker,
            @NonNull UUID userId,
            TurnLogService turnLogService,
            BackgroundTaskManager backgroundTaskManager) {
        this.agentId = agentId;
        this.persona = persona;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        this.toolEngine = toolEngine;
        this.responses = new ResponseValidator(toolEngine, objectMapper);
        this.gateway = gateway;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.toolResultPresenter = new ToolResultPresenter(objectMapper);
        this.breaker = new LoopBreaker(maxCallsPerEpisode);
        this.readHistory = gateway.readHistory();
        this.binding = binding;
        this.program = new GuidedProgram(objectMapper);
        // agentId is the persona id (a UUID string — see AgentService.createAgent); derive the
        // per-session frame key once. Fail-fast if a non-UUID id ever reaches here.
        this.sessionId = UUID.fromString(agentId);
        this.events = new AgentEvents(agentId, objectMapper, deltaBroker, () -> sessionId);
        hitlRegistry.setSession(agentId, this.sessionId);
        this.userId = userId;
        this.journal = new AgentHistory(turnLogService, () -> sessionId, userId, agentId);
        this.backgroundTaskManager = backgroundTaskManager;
    }

    public void setToolResultPresentation(
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        this.toolResultPresentation = toolResultPresentation;
    }

    // ── Virtual-thread loop ────────────────────────────────────────────────

    private MonitorService monitorService;
    private volatile boolean waitingForMonitor;
    private String activeRequestId;
    private String activeMonitorEventId;
    private final @NonNull Map<String, RequestContinuation> requestContinuations = new HashMap<>();
    private CompletableFuture<AgentResult> monitorResultFuture;
    private Consumer<AgentResult> monitorCallback;
    private final @NonNull Map<String, ActivatedObservation> activatedMonitorEvents =
            new LinkedHashMap<>();

    private record ActivatedObservation(MonitorRecord.@NonNull Event event, String requestId) {}

    private record RequestContinuation(@NonNull String task, long consumedCalls) {}

    private RequestContinuationStore continuationStore;

    public void attachContinuationStore(@NonNull RequestContinuationStore store) {
        continuationStore = store;
    }

    private RequestContinuation findContinuation(@NonNull String requestId) {
        RequestContinuation value = requestContinuations.get(requestId);
        RequestContinuationStore store = continuationStore;
        if (value == null && store != null) {
            var saved = store.load(sessionId, agentId, requestId).orElse(null);
            if (saved != null) {
                value = new RequestContinuation(saved.task(), saved.consumedCalls());
                requestContinuations.put(requestId, value);
            }
        }
        return value;
    }

    private void reserveRequestCall() {
        breaker.recordModelCall();
        RequestContinuationStore store = continuationStore;
        String request = activeRequestId;
        if (store != null && request != null)
            store.save(sessionId, agentId, request, activeUserTask, breaker.count());
        rememberRequest();
    }

    private void rememberRequest() {
        String requestId = activeRequestId;
        if (requestId != null)
            requestContinuations.put(
                    requestId, new RequestContinuation(activeUserTask, breaker.count()));
    }

    private boolean belongsToActiveRequest(MonitorRecord.@NonNull Event event) {
        return activeMonitorEventId != null
                ? activeMonitorEventId.equals(event.id())
                : activeRequestId != null && activeRequestId.equals(event.requestId());
    }

    private final @NonNull AtomicBoolean monitorQueued = new AtomicBoolean();

    public void attachMonitor(@NonNull MonitorService service) {
        this.monitorService = service;
    }

    public void signalMonitor() {
        if (sessionAlive && monitorQueued.compareAndSet(false, true))
            actionQueue.add(new AgentAction.MonitorAction());
    }

    /** Exclude cancelled requests even while their observation receipt awaits persistence. */
    private @NonNull List<MonitorRecord.Event> pendingActiveRequestEvents(
            @NonNull MonitorService service) {
        List<MonitorRecord.Event> eligible = new ArrayList<>();
        for (MonitorRecord.Event event : service.pending(agentId, sessionId.toString())) {
            String request = event.requestId();
            boolean cancelled =
                    request != null
                            && history().stream()
                                    .anyMatch(
                                            turn ->
                                                    turn.type() == TurnType.EXECUTION_ERROR
                                                            && "CANCELLED"
                                                                    .equals(
                                                                            turn.payload()
                                                                                    .get("outcome"))
                                                            && request.equals(
                                                                    turn.payload()
                                                                            .get("requestId")));
            if (!cancelled) {
                eligible.add(event);
                continue;
            }
            try {
                service.activationCancelled(agentId, sessionId.toString(), event);
            } catch (RuntimeException error) {
                log.warn("Cancelled request observation {} awaits persistence", event.id(), error);
            }
        }
        return eligible;
    }

    /** Append before acknowledging so a crash cannot silently consume an observation. */
    private boolean injectMonitorEvents() {
        MonitorService service = monitorService;
        if (service == null) return false;
        checkExecutionBoundary();
        boolean inserted = false;
        for (MonitorRecord.Event event : pendingActiveRequestEvents(service)) {
            if (!belongsToActiveRequest(event)) continue;
            boolean recorded =
                    history().stream().anyMatch(t -> event.id().equals(t.payload().get("eventId")));
            if (!recorded) {
                String originRequestId = event.requestId();
                String originDispatchId = event.dispatchId();
                appendTurn(
                        new TurnRecord(
                                ++turnNumber,
                                TurnType.MONITOR_EVENT,
                                Map.of(
                                        "eventId",
                                        event.id(),
                                        "monitorId",
                                        event.monitorId(),
                                        "kind",
                                        event.kind(),
                                        "content",
                                        "Notification for the following originating task (later"
                                                + " user requests remain separate):\n"
                                                + activeUserTask
                                                + "\n\nObservation:\n"
                                                + event.content(),
                                        "requestId",
                                        originRequestId == null ? "" : originRequestId,
                                        "dispatchId",
                                        originDispatchId == null ? "" : originDispatchId),
                                event.occurredAt()));
            }
            service.acknowledge(agentId, event);
            service.activationStarted(agentId, event);
            activatedMonitorEvents.put(
                    event.id(), new ActivatedObservation(event, activeRequestId));
            // A retried acknowledgement still needs reasoning, even if the history already exists.
            inserted = true;
        }
        return inserted;
    }

    private void completeOrWaitForMonitor() {
        MonitorService service = monitorService;
        if (service != null
                && (service.hasGroupWork(agentId, activeRequestId)
                        || service.hasUndeliveredGroup(agentId, activeRequestId))) {
            waitingForMonitor = true;
            transitionTo(AgentState.WAITING);
            signalMonitor();
        } else completeSuccess();
    }

    private void processMonitor() {
        KeysteadVault vault = monitorVault;
        String monitorOwner = owner;
        if (vault != null && (monitorOwner == null || !vault.isUnlocked(monitorOwner))) return;
        MonitorService service = monitorService;
        if (service == null
                || recoveredWait
                || executionWait != null
                || awaitingBreakerContinuation
                || state == AgentState.PAUSED
                || state == AgentState.INTERCEPTED
                || !sessionAlive) return;
        synchronized (this) {
            // A newly submitted user task owns its own handoff future and goes first.
            if (actionQueue.stream()
                    .anyMatch(
                            a ->
                                    a instanceof AgentAction.UserPromptAction
                                            || a instanceof AgentAction.DirectUserPromptAction))
                return;
            var events = pendingActiveRequestEvents(service);
            if (events.isEmpty()) return;
            if (waitingForMonitor) {
                if (events.stream().noneMatch(this::belongsToActiveRequest)) return;
            } else {
                rememberRequest();
                MonitorRecord.Event first = null;
                for (MonitorRecord.Event candidate : events) {
                    String candidateOrigin = candidate.requestId();
                    if (candidateOrigin == null || findContinuation(candidateOrigin) != null) {
                        first = candidate;
                        break;
                    }
                }
                if (first == null) return;
                String origin = first.requestId();
                String requestId = origin == null ? "monitor:" + first.id() : origin;
                RequestContinuation continuation = findContinuation(requestId);
                if (origin != null && continuation == null) {
                    log.warn(
                            "Monitor event {} awaits unavailable request context {}",
                            first.id(),
                            origin);
                    return;
                }
                activeRequestId = requestId;
                activeMonitorEventId = origin == null ? first.id() : null;
                if (continuation != null) {
                    activeUserTask = continuation.task();
                    breaker.restoreCount(continuation.consumedCalls());
                } else {
                    activeUserTask =
                            "Handle this notification without repeating completed work: "
                                    + first.content();
                    if (first.kind().equals("TIME_ONCE")) breaker.newEpisode();
                }
                program.reset();
                handlingDirectUserPrompt = false;
            }
            if (resultFuture.isDone() && !handlingDirectUserPrompt) {
                resultFuture = new CompletableFuture<>();
                callback = null;
            }
            monitorResultFuture = resultFuture;
            monitorCallback = callback;
            waitingForMonitor = false;
            transitionTo(AgentState.RUNNING);
        }
        try {
            refreshSystemHistory();
            boolean inserted = injectMonitorEvents();
            if (!inserted) return;
            preparedFirstPrompt = null;
            program.reset();
            completionToolFinished = false;
            pendingResponse = null;
            submissionRequest = null;
            runAutonomous();
            completeOrWaitForMonitor();
        } catch (BreakerTripException e) {
            completeBreaker();
        } catch (Exception e) {
            completeFailure(failureMessage(e));
        } finally {
            rememberRequest();
            monitorResultFuture = null;
            monitorCallback = null;
            if (sessionAlive && !waitingForMonitor && state != AgentState.PAUSED)
                transitionTo(AgentState.IDLE);
        }
    }

    private volatile Thread runningThread;

    public void run() {
        runningThread = Thread.currentThread();
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
            while (sessionAlive && state != AgentState.TERMINATED) {
                try {
                    AgentAction action = actionQueue.take();
                    if (action instanceof AgentAction.MonitorAction) {
                        monitorQueued.set(false);
                        processMonitor();
                        continue;
                    }
                    if (action instanceof AgentAction.TerminateAction) {
                        transitionTo(AgentState.TERMINATED);
                        break;
                    }
                    if (action instanceof AgentAction.CompactAction) {
                        transitionTo(AgentState.RUNNING);
                        try {
                            checkExecutionBoundary();
                            processCompaction();
                            completeSuccess();
                        } catch (Exception e) {
                            log.error("Agent {} compaction failed", agentId, e);
                            completeFailure(failureMessage(e));
                        } finally {
                            // Clear a stale interrupt flag (see the UserPromptAction finally).
                            if (Thread.interrupted()) {
                                log.debug(
                                        "Agent {} cleared a stale interrupt after compaction",
                                        agentId);
                            }
                            if (sessionAlive && !waitingForMonitor) transitionTo(AgentState.IDLE);
                        }
                        continue;
                    }
                    if (action instanceof AgentAction.UserPromptAction
                            || action instanceof AgentAction.DirectUserPromptAction) {
                        if (waitingForMonitor
                                && action instanceof AgentAction.DirectUserPromptAction direct) {
                            deferredUserPrompts.add(direct);
                            // A prior monitor wake may have yielded to this queued prompt.
                            signalMonitor();
                            continue;
                        }
                        handlingDirectUserPrompt =
                                action instanceof AgentAction.DirectUserPromptAction;
                        String prompt =
                                action instanceof AgentAction.UserPromptAction upa
                                        ? upa.prompt()
                                        : ((AgentAction.DirectUserPromptAction) action).prompt();
                        TaskCancellation taskCancellation;
                        synchronized (this) {
                            taskCancellation = taskActions.remove(action);
                            activeCancellation = taskCancellation;
                        }
                        transitionTo(AgentState.RUNNING);
                        try {
                            checkTaskCancellation();
                            waitingForMonitor = false;
                            checkExecutionBoundary();
                            processUserPrompt(prompt);
                            checkTaskCancellation();
                            completeOrWaitForMonitor();
                        } catch (BreakerTripException e) {
                            completeBreaker();
                        } catch (Exception e) {
                            if (taskCancellation != null && taskCancellation.cancelled) {
                                synchronized (this) {
                                    // Wait for cancelTask to finish sending the one interrupt.
                                    clearTaskInterrupt();
                                }
                                completeFailure(
                                        Msg.get(locale, "error.agent.taskCancelled"),
                                        true,
                                        taskCancellation.requestId);
                            } else {
                                log.error("Agent {} task failed", agentId, e);
                                completeFailure(failureMessage(e));
                            }
                        } finally {
                            if (!waitingForMonitor) handlingDirectUserPrompt = false;
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
                                        agentId);
                            }
                            if (sessionAlive && !waitingForMonitor) transitionTo(AgentState.IDLE);
                            synchronized (this) {
                                activeCancellation = null;
                                clearTaskInterrupt();
                                if (taskCancellation != null) {
                                    cancellableTasks.remove(taskCancellation.result);
                                    lastExitedTask = taskCancellation.result;
                                    taskCancellation.exited.complete(true);
                                }
                            }
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
            log.error("Agent {} runtime linkage failed", agentId, error);
            completeFailure(failureMessage(error));
        } finally {
            runningThread = null;
            try {
                terminate();
            } finally {
                UserContext.clear();
            }
        }
    }

    // ── Episode setup + autonomous loop ─────────────────────────────────────

    private void processUserPrompt(@NonNull String prompt) {
        prompt =
                captureUserPrompt(
                        workflow(
                                prompt, (hook, text) -> hook.beforeInput(workflowContext(), text)));
        if (recoveredWait) {
            appendTurn(
                    new TurnRecord(
                            ++turnNumber,
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
        completionToolFinished = false;
        pendingResponse = null;
        submissionRequest = null;
        declinedCallSignatures.clear();
        // Actively tell the agent about background tasks that ended since it last ran — drained
        // into the context BEFORE the new user prompt so the model reads them together. This is
        // the push half of the task lifecycle (the UI gets TASK_EXITED live; the agent gets it
        // here on its next turn instead of having to remember to poll view_task).
        injectPendingTaskExitNotices();
        // Fresh UserPromptAction: reset guided state and program counter. An exact "continue" has
        // special semantics only immediately after a breaker trip. Preserve the literal user input
        // in history while attaching the prior task for prompt compilation; otherwise a long,
        // budget-trimmed episode re-anchors on the context-free word "continue".
        String resumeContext =
                awaitingBreakerContinuation && "continue".equalsIgnoreCase(prompt.strip())
                        ? (activeUserTask.isBlank() ? latestUserTaskContext() : activeUserTask)
                        : null;
        rememberRequest();
        activeMonitorEventId = null;
        if (resumeContext == null || activeRequestId == null)
            activeRequestId = UUID.randomUUID().toString();
        this.activeUserTask = resumeContext != null ? resumeContext : prompt;
        saveExecutionWait(null);
        injectMonitorEvents();
        awaitingBreakerContinuation = false;
        refreshSystemHistory();
        TurnRecord prospectiveUserTurn =
                resumeContext != null
                        ? TurnRecord.breakerContinuation(turnNumber + 1, prompt, resumeContext)
                        : TurnRecord.userPrompt(turnNumber + 1, prompt);
        List<TurnRecord> prospectiveHistory;
        synchronized (this) {
            prospectiveHistory = new ArrayList<>(history());
        }
        prospectiveUserTurn = withRequestId(prospectiveUserTurn);
        prospectiveHistory.add(prospectiveUserTurn);
        preparedFirstPrompt = compilePrompt(prospectiveHistory, false);
        appendTurn(
                withRequestId(
                        resumeContext != null
                                ? TurnRecord.breakerContinuation(
                                        ++turnNumber, prompt, resumeContext)
                                : TurnRecord.userPrompt(++turnNumber, prompt)));
        TaskCancellation cancellation = activeCancellation;
        if (cancellation != null) cancellation.requestId = activeRequestId;
        program.reset();
        breaker.newEpisode();

        runAutonomous();
    }

    private @NonNull TurnRecord withRequestId(@NonNull TurnRecord turn) {
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        String requestId = activeRequestId;
        if (requestId == null) throw new IllegalStateException("User request identity is missing");
        payload.put("requestId", requestId);
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    private String latestUserTaskContext() {
        List<TurnRecord> history = history();
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

    /**
     * Drains background-task exit notices queued for this agent and appends each as a user-role
     * observation, so the model is actively told which of its tasks ended while it was idle (and
     * their exit codes) rather than having to remember to poll {@code view_task}. No-op when no
     * task manager is configured or nothing exited.
     */
    private void injectPendingTaskExitNotices() {
        if (backgroundTaskManager == null) {
            return;
        }
        if (monitorService != null) {
            backgroundTaskManager.drainExitNotices(agentId);
            return;
        }
        for (BackgroundTaskManager.TaskExitNotice notice :
                backgroundTaskManager.drainExitNotices(agentId)) {
            String prefix =
                    "[notice] background task " + notice.taskId() + " (" + notice.command() + ") ";
            String text =
                    switch (notice.cause()) {
                        case NATURAL ->
                                prefix
                                        + "exited on its own with code "
                                        + notice.exitCode()
                                        + (notice.exitCode() != 0
                                                ? " — a non-zero code means it crashed."
                                                : ".")
                                        + " Launch it again with run_task if needed.";
                        case AGENT_STOP ->
                                prefix
                                        + "was stopped by you (stop_task). It is no longer"
                                        + " running.";
                        case USER_STOP ->
                                prefix
                                        + "was stopped by the user. It is no longer running —"
                                        + " launch it again with run_task only if asked.";
                        case AUTO_KILL ->
                                prefix
                                        + "was auto-killed because its timeout elapsed. It is"
                                        + " no longer running.";
                        case SHUTDOWN ->
                                prefix
                                        + "was terminated during server/agent cleanup. It is"
                                        + " no longer running.";
                    };
            appendObservation("task_exited", text);
        }
    }

    private void processCompaction() {
        int lastInitIndex = -1;
        List<TurnRecord> history = history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).type() == TurnType.AGENT_INIT) {
                lastInitIndex = i;
                break;
            }
        }
        int anchorIndex = lastInitIndex != -1 ? lastInitIndex : 0;

        List<TurnRecord> workTurns = new ArrayList<>();
        if (anchorIndex >= history.size() - 1) {
            emitMessage(Msg.get(locale, "error.agent.compactNothing"));
            return;
        }
        for (int i = anchorIndex + 1; i < history.size(); i++) {
            workTurns.add(history.get(i));
        }

        String finalSummary = computeCompactionSummary(workTurns);
        if ("{}".equals(finalSummary)) {
            appendObservation(
                    "compaction_failed",
                    "No valid summary was produced; the context was retained.");
            return;
        }

        appendTurn(TurnRecord.rewind(++turnNumber, 0));
        appendAgentInit(linkCurrentSystemMessage());
        appendTurn(TurnRecord.compactionSummary(++turnNumber, finalSummary));
        emitMessage(Msg.get(locale, "error.agent.compactDone", workTurns.size()));
        // Domain event: the session compacted. Subscribers can mark the ledger boundary without
        // inferring it from the message text.
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.COMPACTION)
                        .attr("turnNumber", turnNumber)
                        .attr("compactedTurns", workTurns.size())
                        .text(finalSummary)
                        .build());
    }

    /**
     * Summarizes a slice of work turns into a structured JSON record (chunked, then combined).
     * Shared by {@link #processCompaction} (the explicit compact action) and {@link
     * #transformToLeader} (the delegation transform carries the essence of the prior standalone
     * session forward as a COMPACTION_SUMMARY). Returns {@code "{}"} when there is nothing to
     * summarize; never null.
     */
    private @NonNull String computeCompactionSummary(@NonNull List<TurnRecord> workTurns) {
        return new HistoryCompactor(
                        objectMapper,
                        (system, user) -> requests().compactionRequest(system, user),
                        this::performCompactionCall)
                .summarize(workTurns);
    }

    private static void clearTaskInterrupt() {
        if (Thread.interrupted()) {
            log.debug("Cleared task interrupt before lifecycle cleanup");
        }
    }

    private @NonNull VetoResponse performCompactionCall(@NonNull VetoRequest request) {
        VetoResponse response;
        LlmSystemUsage.begin();
        try {
            checkTaskCancellation();
            response = callModelWithHooks(request);
            checkTaskCancellation();
        } finally {
            for (LlmSystemUsage.Usage measured : LlmSystemUsage.drain()) {
                UsageMeasurement data =
                        UsageMeasurement.measured(request, measured).forCompaction();
                recordUsage(turnNumber, data);
            }
        }
        return response;
    }

    private VetoRequest submissionRequest;
    private boolean submissionGeneration;
    private ToolCallContextHolder.ResponseDirective pendingResponse;

    private @NonNull VetoResponse takeResponse(
            ToolCallContextHolder.ResponseDirective.@NonNull Answer directive) {
        pendingResponse = null;
        lastCitations = directive.citations();
        return directive.response();
    }

    private ToolCallContextHolder.@NonNull ResponseDirective validateSubmission(
            @NonNull ResponseRequest submission) throws Exception {
        return responses.validateSubmission(
                submission,
                submissionRequest,
                submissionGeneration,
                completionTool,
                whitelistedTools,
                history(),
                gateway);
    }

    private ResponseSubmission.Kind submissionKind(@NonNull String name) {
        return responses.submissionKind(name);
    }

    private void runAutonomous() {
        while (state == AgentState.RUNNING) {
            checkTaskCancellation();
            // Mid-episode task lifecycle: a background task that ended (or that the user
            // stopped) during THIS episode is reported at the next iteration, not only at the
            // start of the next episode. Cheap no-op when the queue is empty.
            // processUserPrompt already drained notices before preparing the first immutable
            // request. Do not mutate history between that compilation and its dispatch.
            if (preparedFirstPrompt == null) {
                injectPendingTaskExitNotices();
                injectMonitorEvents();
            }
            if (pendingResponse == null && breaker.shouldTrip()) {
                tripBreaker();
                throw new BreakerTripException();
            }
            var accepted = pendingResponse;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Plan plan) {
                pendingResponse = null;
                checkTaskCancellation();
                program.install(plan.program(), lastModelCallId);
                AgentPersona programPersona = persona;
                runGuided();
                if (persona != programPersona) continue;
                return;
            }
            VetoResponse response =
                    accepted instanceof ToolCallContextHolder.ResponseDirective.Answer answer
                            ? takeResponse(answer)
                            : callModel();
            checkTaskCancellation();

            appendThought(response);
            String message = response.message();
            if (message != null && !message.isBlank()) {
                var calls = response.calls();
                emitMessage(
                        message,
                        lastCitations,
                        lastModelCallId,
                        false,
                        calls != null
                                && calls.stream().anyMatch(call -> call.nativeState() != null));
            }
            List<ToolCall> responseCalls = response.calls();
            if (responseCalls != null && !responseCalls.isEmpty()) {
                executeToolCalls(responseCalls, response.thought());
                if (completionToolFinished) return;
            } else {
                // No tool calls: the agent has emitted its answer with nothing further to act
                // on. Termination routes on call presence - calls absent means stop. The agent
                // reasons within its model invocation. Stop the episode here; the emitted
                // message is the final answer.
                return;
            }
        }
    }

    // ── Guided loop (drives the actions program IR) ─────────────────────────

    private GuidedStepContext currentGuidedStep;

    private void runGuided() {
        var runtime = guidedRuntime();
        while (state == AgentState.RUNNING && program.active()) {
            checkTaskCancellation();
            injectPendingTaskExitNotices();
            injectMonitorEvents();
            program.step(runtime);
        }
    }

    private GuidedProgram.@NonNull Runtime guidedRuntime() {
        return new GuidedProgram.Runtime() {
            @Override
            public boolean running() {
                return state == AgentState.RUNNING;
            }

            @Override
            public @NonNull ToolResult tool(
                    @NonNull ToolCall call, @NonNull GuidedStepContext context) {
                currentToolModelCallId = context.programModelCallId();
                currentGuidedStep = context;
                try {
                    return executeOneCall(call);
                } finally {
                    currentToolModelCallId = null;
                    currentGuidedStep = null;
                }
            }

            @Override
            public GuidedProgram.@NonNull Generated generate(
                    @NonNull GenerateAction action, @NonNull ResponseContract contract) {
                VetoResponse response = callGenerate(action, contract);
                return new GuidedProgram.Generated(response, lastCitations, lastModelCallId);
            }

            @Override
            public void message(
                    @NonNull String text,
                    MessageCitations.Bound citations,
                    String callId,
                    boolean forwarded) {
                emitMessage(text, citations, callId, forwarded);
            }

            @Override
            public void escaped(@NonNull String reason) {
                appendObservation("guided_escape", reason);
            }
        };
    }

    private @NonNull VetoResponse callGenerate(
            @NonNull GenerateAction gen, @NonNull ResponseContract contract) {
        if (breaker.shouldTrip()) {
            tripBreaker();
            throw new BreakerTripException();
        }
        VetoResponse response;
        while (true) {
            response = callModel(gen, contract);
            if (!Boolean.FALSE.equals(gen.thought())) appendThought(response);
            var calls = response.calls();
            if (calls == null || calls.isEmpty()) break;
            executeToolCalls(calls, response.thought());
            var accepted = pendingResponse;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Answer answer) {
                response = takeResponse(answer);
                break;
            }
            checkTaskCancellation();
        }
        return response;
    }

    // ── The model call (compile + dispatch + enforce, with schema retry) ────

    private @NonNull VetoResponse callModel() {
        String completion = completionTool;
        return callModel(
                null,
                completion == null
                        ? ResponseContract.ordinary()
                        : ResponseContract.completion(completion, false));
    }

    private @NonNull VetoResponse callModel(
            GenerateAction generation, @NonNull ResponseContract contract) {
        lastCitations = null;
        CompiledPrompt compiled = preparedFirstPrompt;
        preparedFirstPrompt = null;
        if (compiled == null) {
            refreshSystemHistory();
            compiled = compilePrompt(history(), generation != null);
        }
        VetoRequest request = requests().buildRequest(compiled).withResponseContract(contract);
        if (generation != null)
            request = requests().generationRequest(request, generation, program.scope());
        try {
            var result =
                    new ModelExchange(agentId, responses)
                            .complete(
                                    request,
                                    requests(),
                                    whitelistedTools,
                                    this::history,
                                    modelRuntime(),
                                    correctionFactor);
            lastCitations = result.citations();
            lastModelCallId = result.modelCallId();
            if (result.accepted()) {
                submissionRequest = result.request();
                submissionGeneration = generation != null;
            }
            return result.response();
        } catch (LlmException e) {
            // LLM failure → record error, break the loop ( table: LLM Error → IDLE).
            TaskCancellation cancellation = activeCancellation;
            if (cancellation == null || !cancellation.cancelled) {
                appendObservation(
                        "llm_error",
                        e.getMessage() == null
                                ? "LLM call failed without a message"
                                : e.getMessage());
            }
            transitionTo(AgentState.IDLE);
            throw e;
        }
    }

    private ModelExchange.@NonNull Runtime modelRuntime() {
        return new ModelExchange.Runtime() {
            @Override
            public @NonNull VetoRequest prepare(@NonNull VetoRequest request) {
                return completionOnly(breaker.count())
                        ? requests()
                                .completionRequest(
                                        request,
                                        completionTool,
                                        breaker.maxCallsPerEpisode() - breaker.count())
                        : request;
            }

            @Override
            public ModelExchange.@NonNull Attempt invoke(
                    @NonNull VetoRequest request, double estimateFactor) {
                return performModelCall(request, estimateFactor);
            }

            @Override
            public void rejected(@NonNull ModelSchemaException e) {
                appendTurn(
                        new TurnRecord(
                                ++turnNumber,
                                TurnType.EXECUTION_ERROR,
                                Map.of(
                                        "content",
                                        String.valueOf(e.getMessage()),
                                        "recoverable",
                                        true,
                                        "errorCode",
                                        "MODEL_RESPONSE_REJECTED"),
                                null));
            }
        };
    }

    private ModelExchange.@NonNull Attempt performModelCall(
            @NonNull VetoRequest request, double estimateFactor) {
        long estimatedTokens;
        VetoResponse response;
        if (breaker.shouldTrip()) {
            tripBreaker();
            throw new BreakerTripException();
        }
        checkExecutionBoundary();
        reserveRequestCall();
        request = promptCompiler.fitRequest(request, correctionFactor);
        estimatedTokens = promptCompiler.estimateRequest(request, estimateFactor);
        log.debug(
                "Agent {} input: model={}, messages={}, estimatedTokens={},"
                        + " correctionFactor={}",
                agentId,
                request.modelName(),
                request.messages().size(),
                estimatedTokens,
                correctionFactor);
        int requestThroughTurn = turnNumber;
        lastModelCallId = UUID.randomUUID().toString();
        LlmSystemUsage.begin();
        try {
            checkTaskCancellation();
            lastPluginContext =
                    PluginContextSnapshot.from(
                            toolEngine.getActiveTools(
                                    request.tools().stream()
                                            .map(top.focess.veto.api.llm.ToolDefinition::name)
                                            .collect(Collectors.toSet())),
                            true);
            response = callModelWithHooks(request);
            checkTaskCancellation();
        } finally {
            List<LlmSystemUsage.Usage> measurements = LlmSystemUsage.drain();
            for (LlmSystemUsage.Usage measured : measurements) {
                UsageMeasurement measurement = UsageMeasurement.measured(request, measured);
                lastModelCallId = UUID.randomUUID().toString();
                measurement = measurement.forRequest(lastModelCallId);
                recordUsage(requestThroughTurn, measurement);
            }
            if (!measurements.isEmpty()
                    && estimatedTokens > 0
                    && measurements.getLast().promptTokens() > 0) {
                double rawRatio =
                        measurements.getLast().promptTokens() * estimateFactor / estimatedTokens;
                this.correctionFactor = 0.9 * correctionFactor + 0.1 * rawRatio;
            }
        }

        return new ModelExchange.Attempt(request, response, lastModelCallId);
    }

    private boolean completionOnly(long completedCalls) {
        long limit = breaker.maxCallsPerEpisode();
        return completionTool != null
                && limit > 0
                && completedCalls >= limit - (limit >= 4 ? 2 : 1);
    }

    private @NonNull ModelRequests requests() {
        return new ModelRequests(
                promptCompiler,
                gateway.workspace(),
                persona,
                binding,
                toolResultPresentation,
                owner,
                guidedTierRegistry,
                responses);
    }

    private @NonNull CompiledPrompt compilePrompt(
            @NonNull List<TurnRecord> history, boolean scoped) {
        return requests().compilePrompt(history, scoped, correctionFactor, recoveryContext);
    }

    private PromptSource.Rendered currentSystemSource;

    private @NonNull String linkCurrentSystemMessage() {
        PromptSource.Rendered source =
                promptCompiler.linkSystemSource(
                        persona,
                        gateway.workspace(),
                        binding.systemPromptBase(),
                        toolResultPresentation);
        currentSystemSource = source;
        return source.text();
    }

    /** Record configuration changes explicitly rather than silently recompiling an old init. */
    private void refreshSystemHistory() {
        List<TurnRecord> additions;
        List<TurnRecord> history = history();
        additions =
                HistoryProjection.reinitialize(
                        history,
                        turnNumber,
                        persona.role().name(),
                        linkCurrentSystemMessage(),
                        binding.provider().name(),
                        binding.model());
        for (TurnRecord record : additions) {
            turnNumber = record.turnNumber();
            appendTurn(record);
        }
    }

    /**
     * The transform fallback when no valid compaction summary exists: discard the compiled view
     * (REWIND to 0), record the new role's AGENT_INIT, and retain the prior conversation as
     * restored copies. Unlike {@link #refreshSystemHistory()} (an in-place context update), the
     * delegation transform must record the boundary REWIND.
     */
    private void rewindAndRestoreHistory() {
        List<TurnRecord> additions;
        List<TurnRecord> history = history();
        additions =
                HistoryProjection.reinitializeWithRewind(
                        history,
                        turnNumber,
                        persona.role().name(),
                        linkCurrentSystemMessage(),
                        binding.provider().name(),
                        binding.model());
        for (TurnRecord record : additions) {
            turnNumber = record.turnNumber();
            appendTurn(record);
        }
    }

    private void appendAgentInit(@NonNull String systemPrompt) {
        LlmBinding current = binding;
        String role = persona.role().name().toLowerCase(Locale.ROOT);
        appendTurn(
                TurnRecord.agentInit(
                        ++turnNumber,
                        role,
                        systemPrompt,
                        current.provider().name(),
                        current.model()));
    }

    // ── executeToolCalls — the canonical chain ─────────────────────

    private void executeToolCalls(@NonNull List<ToolCall> calls, String thought) {
        transitionTo(AgentState.WAITING);
        currentToolModelCallId = lastModelCallId;
        try {

            if (calls.size() > 1
                    && calls.stream().anyMatch(c -> submissionKind(c.toolName()) != null)) {
                for (var call : calls) {
                    appendToolCall(call);
                    appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            "A response submission must be the only tool call. No calls in this"
                                    + " batch were executed; resubmit separately.",
                            false);
                }
                return;
            }
            List<ToolCall> callsNeedingDecision = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                if (declinedCallSignatures.contains(toolCallSignature(call))) {
                    appendToolCall(call);
                    appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            refusedObservation(
                                    PromptCompiler.compileText("runtime-refused-duplicate")),
                            false);
                } else {
                    callsNeedingDecision.add(call);
                }
            }
            if (callsNeedingDecision.isEmpty()) {
                return;
            }
            calls = callsNeedingDecision;

            // 1. Check phase (screen all calls first)
            List<ApprovalDecision> decisions = new ArrayList<>();
            List<ToolExecutionPermit> executionPermits = new ArrayList<>();
            boolean hasVeto = false;
            boolean hasRefused = false;
            for (ToolCall call : calls) {
                ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
                if (def == null) {
                    decisions.add(ApprovalDecision.AUTO_APPROVE);
                    executionPermits.add(ToolExecutionPermit.empty());
                } else {
                    var hookDecision = beforeToolHooks(call);
                    var result =
                            def instanceof AgentToolDefinition
                                    ? new GatewayResult.NotScreened()
                                    : screenToolCall(call, def, thought);
                    executionPermits.add(result.executionPermit());
                    ApprovalDecision decision =
                            hitlRegistry.decide(agentId, call, def, result, hookDecision);
                    decisions.add(decision);
                    if (decision instanceof ApprovalDecision.Prompt) hasVeto = true;
                    else if (decision instanceof ApprovalDecision.Refused) hasRefused = true;
                }
            }

            // 2. Hold phase
            List<ToolCall> skippedCalls = new ArrayList<>();
            if (hasVeto || hasRefused) {
                boolean batchApproved = true;
                // Why the batch aborts - recorded into the synthesized REFUSED observations so
                // the model (next prompt) and the audit reader can tell user-decline apart from
                // policy-refusal. A bare "REFUSED" string carries no information.
                String refusalDetail = "declined";
                boolean approvalRequested = false;
                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    String callId = call.callId();
                    ApprovalDecision decision = decisions.get(i);
                    ToolDefinition def = toolEngine.resolveDefinition(call.toolName());

                    if (declinedCallSignatures.contains(toolCallSignature(call))) {
                        skippedCalls.add(call);
                    } else if (decision instanceof ApprovalDecision.Refused r) {
                        emitMessage(r.reason());
                        transitionTo(AgentState.INTERCEPTED);
                        if (def == null) {
                            throw new IllegalStateException("Refusal without a tool definition");
                        }
                        List<VetoOption> offered = List.of(VetoOption.EXEC_DECLINE);
                        hitlRegistry.register(
                                agentId, callId, call, def, offered, Danger.CRITICAL, null);
                        emitVetoRequired(
                                call,
                                new ApprovalDecision.Prompt(
                                        VetoScenario.GENERIC, offered, Danger.CRITICAL, null),
                                offered);
                        awaitResolution(callId);
                        refusalDetail =
                                "refused by the security policy (CRITICAL - no approval path)";
                        batchApproved = false;
                        break;
                    } else if (decision instanceof ApprovalDecision.Prompt p) {
                        transitionTo(AgentState.INTERCEPTED);
                        // Register the await target BEFORE advertising the prompt: the veto
                        // listener sends the Prompt synchronously, and the user's reply could
                        // otherwise race register and resolve against a not-yet-registered future.
                        List<VetoOption> offered = p.options();
                        if (def == null) {
                            throw new IllegalStateException(
                                    "Prompt decision without a tool definition for "
                                            + call.toolName());
                        }
                        hitlRegistry.register(
                                agentId, callId, call, def, offered, p.danger(), p.relevance());
                        emitVetoRequired(call, p, offered);
                        InterceptResolution resolution = awaitResolution(callId);

                        if (resolution.option() == VetoOption.DECLINE_AND_CONTINUE) {
                            skippedCalls.add(call);
                            declinedCallSignatures.add(toolCallSignature(call));
                        } else if (resolution.isRefusal()) {
                            refusalDetail = resolution.refusalReason();
                            approvalRequested = true;
                            batchApproved = false;
                            break;
                        }
                    }
                }

                if (!batchApproved) {
                    // Synthesize ToolResponse(status=REFUSED) for all calls, no execution, go IDLE
                    for (ToolCall call : calls) {
                        appendToolCall(call);
                        appendToolResponse(
                                call.toolName(),
                                call.callId(),
                                refusedObservation(refusalDetail),
                                false);
                    }
                    transitionTo(AgentState.IDLE);
                    throw new VetoRefusedException(approvalRequested);
                }
            }

            // 3. Execute phase (all confirmed / skipped)
            if (state == AgentState.INTERCEPTED) transitionTo(AgentState.WAITING);
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (skippedCalls.contains(call)) {
                    appendToolCall(call);
                    appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            refusedObservation("declined by the client (DECLINE_AND_CONTINUE)")
                                    + " Continue without this call: do not retry it"
                                    + " unchanged - pick a different approach, or explain"
                                    + " the blockage and stop.",
                            false);
                } else {
                    AgentPersona callPersona = persona;
                    ToolResult result = executeOneConfirmedCall(call, executionPermits.get(i));
                    if (result.success() && call.toolName().equals(completionTool)) {
                        lastMessage = result.content();
                        completionToolFinished = true;
                        return;
                    }
                    if (pendingResponse != null) return;
                    if (persona != callPersona) {
                        return; // Remaining calls were authored for the previous role.
                    }
                }
            }

        } finally {
            currentToolModelCallId = null;
            if (state == AgentState.WAITING || state == AgentState.INTERCEPTED) {
                transitionTo(AgentState.RUNNING);
            }
        }
    }

    private @NonNull ToolResult executeOneConfirmedCall(
            @NonNull ToolCall call, @NonNull ToolExecutionPermit executionPermit) {
        ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call);
        }
        return executeResolvedCall(call, def, ApprovalDecision.AUTO_APPROVE, executionPermit);
    }

    private @NonNull ToolResult executeResolvedCall(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            @NonNull ApprovalDecision decision,
            @NonNull ToolExecutionPermit screenedPermit) {
        checkExecutionBoundary();
        appendToolCall(call);

        ToolExecutionPermit executionPermit;
        try {
            executionPermit = gateway.revalidateExecution(call, def, screenedPermit);
        } catch (SecurityException e) {
            String observation =
                    "Filesystem target changed after screening; submit a fresh tool call";
            appendToolResponse(call.toolName(), call.callId(), observation, false);
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    observation,
                    ToolErrorCode.WORKSPACE.TREE_CHANGED);
        }

        // (c) plugin preAction chain
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked by plugin",
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agentId,
                        userId,
                        groupId,
                        owner,
                        sessionId,
                        toolResultPresentation,
                        executionPermit.withCaller(agentId, userId, groupId, owner, sessionId),
                        activeRequestId));
        try {
            if (submissionKind(call.toolName()) != null) {
                var request = submissionRequest;
                if (request != null
                        && request.nativeToolsEnabled()
                        && request.tools().stream().anyMatch(t -> t.name().equals(call.toolName())))
                    ToolCallContextHolder.setResponseHandler(this::validateSubmission);
            }
            // (e) plugin postAction chain
            checkTaskCancellation();
            boolean waitsForAnswer = def.capability() == ToolCapability.USER_INTERACTION;
            if (waitsForAnswer) saveExecutionWait(WaitReason.QUESTION);
            ToolResult transformed = toolEngine.execute(call, def);
            checkTaskCancellation();
            ToolResult actualResult = transformed;
            String hookContent =
                    workflow(
                            actualResult.content(),
                            (hook, content) ->
                                    hook.afterTool(
                                            workflowContext(),
                                            hookInvocation(call),
                                            new WorkflowHook.Output(
                                                    content,
                                                    actualResult.format(),
                                                    actualResult.success())));
            transformed = transformed.withContent(hookContent);
            for (LoopInterceptor plugin : interceptors) {
                transformed = plugin.postAction(agentId, call, transformed);
            }

            // (f) plugin observation transformations are untrusted input to the final defense.
            String pluginObservation =
                    workflow(
                            transformed.content(),
                            (hook, text) -> hook.beforeObservation(workflowContext(), text));
            for (LoopInterceptor plugin : interceptors) {
                pluginObservation = plugin.preObservation(agentId, pluginObservation);
            }
            transformed = transformed.withContent(pluginObservation);

            // (g) final ingress defense, immediately before committing the observation to history.
            String observation;
            var selected = sessionPlugins;
            String currentOwner = owner;
            if (transformed.success()
                    && def instanceof NativeToolDefinition
                    && def.capability() == ToolCapability.WORKSPACE_READ
                    && selected != null
                    && currentOwner != null
                    && selected.has(
                            sessionId.toString(), StandardContributionPoints.FILE_OBSERVATION)) {
                // The owning plugin masks plain segments while preserving SECRET_REF markers.
                String protectedText =
                        selected.protect(
                                StandardContributionPoints.FILE_OBSERVATION,
                                new TextProtection.Scope(
                                        currentOwner, sessionId.toString(), agentId),
                                transformed.content());
                observation =
                        ingressDefense.frameProtectedFile(call, def, transformed, protectedText);
            } else {
                observation =
                        ingressDefense.maskAndFrame(call, def, transformed, decision, readHistory);
            }

            ToolResult observed = transformed.withContent(observation);
            appendToolResponse(observed);
            var responseDirective = ToolCallContextHolder.drainResponse();
            if (observed.success() && responseDirective != null)
                pendingResponse = responseDirective;
            if (waitsForAnswer && sessionAlive) saveExecutionWait(null);

            // Drain any turn directives the tool requested during execution (e.g. a REWIND seeded
            // by create_group to re-inject the authored brief). Each is appended with a
            // runner-assigned turn number; the pending record's placeholder turnNumber is rewritten
            // (type + payload preserved). Drained here, before clear() in the finally, so a tool
            // that threw never leaks a directive to the next call on this thread.
            for (TurnRecord pending : ToolCallContextHolder.drainPendingTurns()) {
                appendTurn(new TurnRecord(++turnNumber, pending.type(), pending.payload(), null));
            }
            // A tool may request a delegation transform (create_group) or its reverse
            // (disband_group).
            // Apply it after the pending turn directives: a forward transform's REWIND discards
            // this
            // call's response + prior turns, then re-seeds the Leader; a reverse transform restores
            // the STANDALONE persona. Drained before clear() in the finally so a throwing tool
            // leaks
            // no transform to the next call on this thread.
            ToolCallContextHolder.TransformRequest transformRequest =
                    ToolCallContextHolder.drainTransform();
            if (transformRequest instanceof ToolCallContextHolder.TransformRequest.ToLeader t) {
                transformToLeader(t.directive());
            } else if (transformRequest
                    instanceof ToolCallContextHolder.TransformRequest.ToStandalone t) {
                transformToStandalone(t.brief());
            }
            return observed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    private @NonNull ToolResult executeOneCall(@NonNull ToolCall call) {
        String callId = call.callId();
        if (!whitelistedTools.contains(call.toolName()))
            throw new SecurityException("Tool is not available in this role: " + call.toolName());
        ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call);
        }

        if (def instanceof LocalToolDefinition local)
            NativeToolArgumentValidator.validate(
                    local.name(), objectMapper.valueToTree(call.args()), local.argsClass());

        var hookDecision = beforeToolHooks(call);
        var result =
                def instanceof AgentToolDefinition
                        ? new GatewayResult.NotScreened()
                        : screenToolCall(call, def, null);
        ToolExecutionPermit executionPermit = result.executionPermit();
        ApprovalDecision decision = hitlRegistry.decide(agentId, call, def, result, hookDecision);
        {
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                appendToolCall(call);
                appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked: " + ab.reason(),
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
            if (decision instanceof ApprovalDecision.Refused r) {
                appendToolCall(call);
                appendToolResponse(
                        call.toolName(), call.callId(), refusedObservation(r.reason()), false);
                throw new VetoRefusedException();
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                ResolvedCall resolvedCall = awaitVeto(call, def, p, executionPermit);
                if (resolvedCall == null) {
                    throw new VetoRefusedException(true);
                }
                call = resolvedCall.call();
                executionPermit = resolvedCall.executionPermit();
                transitionTo(AgentState.RUNNING);
            }
        }

        transitionTo(AgentState.WAITING);
        try {
            return executeResolvedCall(call, def, decision, executionPermit);
        } finally {
            if (state == AgentState.WAITING) transitionTo(AgentState.RUNNING);
        }
    }

    private @NonNull ToolResult toolNotFound(@NonNull ToolCall call) {
        String observation = "Tool not found: " + call.toolName();
        appendToolCall(call);
        appendObservation(call.toolName(), observation);
        return ToolResult.failure(
                call.toolName(), call.callId(), observation, ToolErrorCode.VALIDATION.UNKNOWN_TOOL);
    }

    /**
     * The observation body for a refused call - delegates to {@link RefusalObservation#of(String)},
     * the single owner of the reserved {@code REFUSED - } grammar.
     */
    private static @NonNull String refusedObservation(@NonNull String detail) {
        return RefusalObservation.of(detail);
    }

    private @NonNull String toolCallSignature(@NonNull ToolCall call) {
        try {
            return call.toolName() + '\u0000' + objectMapper.writeValueAsString(call.args());
        } catch (Exception e) {
            return call.toolName() + '\u0000' + call.args();
        }
    }

    private final @NonNull Map<String, ApprovalReceipt> approvalReceipts = new HashMap<>();

    private @NonNull InterceptResolution awaitResolution(@NonNull String callId) {
        InterceptResolution resolution = hitlRegistry.await(agentId, callId);
        synchronized (this) {
            // cancelTask must finish both declining the wait and interrupting this thread first.
            TaskCancellation cancellation = activeCancellation;
            boolean restoreInterrupt =
                    cancellation != null && cancellation.cancelled && Thread.interrupted();
            try {
                if (sessionAlive) saveExecutionWait(null);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
        checkTaskCancellation();
        approvalReceipts.put(
                callId,
                new ApprovalReceipt(
                        resolution.option(), resolution.source(), Instant.now().toString()));
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.VETO_RESOLVED)
                        .attr("agentId", agentId)
                        .attr("callId", callId)
                        .attr("option", resolution.option().name())
                        .attr("refusal", resolution.isRefusal())
                        .build());
        return resolution;
    }

    private record ResolvedCall(
            @NonNull ToolCall call, @NonNull ToolExecutionPermit executionPermit) {}

    private ResolvedCall awaitVeto(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull ToolExecutionPermit executionPermit) {
        transitionTo(AgentState.INTERCEPTED);
        // Register before advertising the prompt so a fast reply cannot beat registration.
        List<VetoOption> offered = p.options();
        String callId = call.callId();
        hitlRegistry.register(agentId, callId, call, def, offered, p.danger(), p.relevance());
        emitVetoRequired(call, p, offered);
        InterceptResolution resolution = awaitResolution(callId);
        transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            appendToolCall(call);
            appendToolResponse(
                    call.toolName(),
                    call.callId(),
                    refusedObservation(resolution.refusalReason()),
                    false);
            return null;
        }
        return new ResolvedCall(call, executionPermit);
    }

    private @NonNull GatewayResult screenToolCall(
            @NonNull ToolCall call, @NonNull ToolDefinition definition, String thought) {
        ProcessInputTarget processInput = processInputTarget(call, definition);
        GatewayResult result =
                gateway.screen(
                        call,
                        definition,
                        activeUserTask,
                        thought,
                        processInput == null ? null : processInput.screeningContext(),
                        currentGuidedStep);
        if (processInput == null) {
            return result;
        }
        ToolExecutionPermit permit =
                result.executionPermit().withTaskBinding(processInput.binding());
        return switch (result) {
            case GatewayResult.Screened screened ->
                    new GatewayResult.Screened(screened.screening(), permit);
            case GatewayResult.DriftResult drift ->
                    new GatewayResult.DriftResult(drift.path(), drift.diff(), permit);
            case GatewayResult.NotScreened ignored -> result;
        };
    }

    private ProcessInputTarget processInputTarget(
            @NonNull ToolCall call, @NonNull ToolDefinition definition) {
        if (!(definition instanceof NativeToolDefinition nativeDefinition)
                || !nativeDefinition.paramHints().containsValue(ParamCategory.PROCESS_INPUT)
                || backgroundTaskManager == null) {
            return null;
        }
        Object rawTaskId = call.args().get("taskId");
        if (!(rawTaskId instanceof String taskId) || taskId.isBlank()) {
            return null;
        }
        BackgroundTaskManager.InputTaskSnapshot snapshot =
                backgroundTaskManager.inputTaskSnapshot(agentId, sessionId, taskId).orElse(null);
        if (snapshot == null) {
            return new ProcessInputTarget(
                    new ToolExecutionPermit.TaskBinding(taskId, agentId, sessionId, new UUID(0, 0)),
                    "No background task with this id exists in the calling agent and session.");
        }
        return new ProcessInputTarget(
                new ToolExecutionPermit.TaskBinding(
                        snapshot.taskId(),
                        snapshot.agentId(),
                        snapshot.sessionId(),
                        snapshot.taskInstanceId()),
                "Target background process: executable="
                        + snapshot.command().executable()
                        + ", argv="
                        + snapshot.command().args()
                        + ", cwd="
                        + snapshot.cwd()
                        + ", networkAllowed="
                        + snapshot.networkAllowed()
                        + ", alive="
                        + snapshot.alive()
                        + ", stdinAvailable="
                        + snapshot.stdinAvailable());
    }

    private record ProcessInputTarget(
            @NonNull TaskBinding binding, @NonNull String screeningContext) {}

    private void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered) {
        events.emitVetoRequired(call, p, offered);
    }

    // ── Turn history + messaging ────────────────────────────────────────────

    private void appendThought(@NonNull VetoResponse response) {
        String thought = response.thought();
        // Provider-exposed reasoning is display text; native replay state lives on tool calls.
        Map<String, Object> payload = new HashMap<>();
        if (thought != null && !thought.isBlank()) {
            payload.put("response", thought);
            payload.put("provider_reasoning", true);
            payload.put("response_format", "text");
        } else {
            return;
        }
        if (lastModelCallId != null) payload.put("model_call_id", lastModelCallId);
        appendTurn(new TurnRecord(++turnNumber, TurnType.ASSISTANT_THOUGHT, payload, null));
        // Stream the thought to transports now (after it is durably recorded). The terminal
        // renders it dimmed/muted ahead of the user-facing message that follows, so the user
        // can follow the reasoning without it competing with the answer.
        emitThought(thought);
    }

    private MessageCitations.Bound lastCitations;
    private String lastModelCallId;
    private String currentToolModelCallId;

    private void emitMessage(@NonNull String message) {
        emitMessage(message, null);
    }

    private void emitMessage(@NonNull String message, MessageCitations.Bound citations) {
        emitMessage(message, citations, null);
    }

    private void emitMessage(
            @NonNull String message, MessageCitations.Bound citations, String modelCallId) {
        emitMessage(message, citations, modelCallId, false);
    }

    private void emitMessage(
            @NonNull String message,
            MessageCitations.Bound citations,
            String modelCallId,
            boolean runtimeForwarded) {
        emitMessage(message, citations, modelCallId, runtimeForwarded, false);
    }

    private void emitMessage(
            @NonNull String message,
            MessageCitations.Bound citations,
            String modelCallId,
            boolean runtimeForwarded,
            boolean nativeResponseText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (runtimeForwarded) payload.put("runtimeOutputTokens", 0L);
        if (nativeResponseText) payload.put("native_response_text", true);
        payload.put("content", message);
        if (modelCallId != null) payload.put("model_call_id", modelCallId);
        if (citations != null && !citations.checks().isEmpty())
            payload.put("citation_context", citations);
        appendTurn(new TurnRecord(++turnNumber, TurnType.ASSISTANT_RESPONSE, payload, null));
        lastMessage = message;
        events.message(message, turnNumber);
    }

    /**
     * Emission seam for interim thoughts, parallel to {@link #emitMessage}: forwards the thought
     * text to each subscribed transport (the terminal renders it distinct from a message) and
     * publishes an {@link DeltaFrame.Kind#ASSISTANT_THOUGHT} to the broker so the web UI gets the
     * same stream. Unlike {@code emitMessage} this does NOT advance the turn history - the thought
     * is already recorded by {@link #appendThought} before calling here.
     */
    private void emitThought(@NonNull String thought) {
        events.thought(thought, turnNumber);
    }

    /**
     * Best-effort publish of a {@link DeltaFrame} to the broker — the single emission point for
     * every event kind. A missing or throwing broker is ignored: events are notifications for
     * subscribers, not load-bearing control flow, so emitting one must never break the loop.
     */
    private void publishFrame(@NonNull DeltaFrame frame) {
        events.publishFrame(frame);
    }

    /**
     * Emits the breaker-trip user message and publishes the {@link DeltaFrame.Kind#BREAKER_TRIPPED}
     * domain event, so subscribers can distinguish a per-episode call-ceiling trip from a normal
     * message. The caller still throws {@code BreakerTripException} to end the episode.
     */
    private void tripBreaker() {
        saveExecutionWait(WaitReason.BREAKER);
        awaitingBreakerContinuation = true;
        String notice = LoopBreaker.tripNotice(locale);
        emitMessage(notice);
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.BREAKER_TRIPPED)
                        .attr("turnNumber", turnNumber)
                        .attr("maxCallsPerEpisode", breaker.maxCallsPerEpisode())
                        .text(notice)
                        .build());
    }

    private void appendObservation(@NonNull String toolName, @NonNull String content) {
        appendToolResponse(toolName, null, content, false);
    }

    private void appendToolResponse(
            @NonNull String toolName, String callId, @NonNull String content, boolean success) {
        appendToolResponse(
                success
                        ? ToolResult.success(toolName, callId, content)
                        : ToolResult.failure(
                                toolName, callId, content, ToolErrorCode.GENERIC.TOOL_FAILURE));
    }

    /** Persists exactly the representation that this session presents to the model. */
    private void appendToolResponse(@NonNull ToolResult result) {
        String presented = toolResultPresenter.present(result, toolResultPresentation);
        TurnRecord turn =
                TurnRecord.presentedToolResponse(
                        ++turnNumber, result, presented, toolResultPresentation);
        String responseCallId = result.callId();
        ApprovalReceipt receipt =
                responseCallId == null ? null : approvalReceipts.remove(responseCallId);
        if (receipt != null) {
            Map<String, Object> payload = new HashMap<>(turn.payload());
            payload.put("approval", receipt);
            turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        }
        appendTurn(turn);
    }

    private void recordUsage(int throughTurn, @NonNull UsageMeasurement measurement) {
        journal.recordUsage(throughTurn, measurement);
    }

    private void appendToolCall(@NonNull ToolCall call) {
        TurnRecord turn = TurnRecord.toolCall(++turnNumber, call);
        String origin = currentToolModelCallId;
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        if (origin != null) payload.put("model_call_id", origin);
        ToolDefinition definition = toolEngine.resolveDefinition(call.toolName());
        if (definition != null) {
            payload.put("tool_origin", definition.origin());
            var provenance = definition.provenance();
            if (provenance != null) {
                payload.put("plugin_id", provenance.pluginId());
            }
        }
        turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        appendTurn(turn);
    }

    private void appendTurn(@NonNull TurnRecord turn) {
        turn = promptCompiler.recordRuntimeSource(turn);
        WaitReason waiting = executionWait;
        boolean required =
                turn.type() == TurnType.MONITOR_EVENT
                        || (turn.type() == TurnType.TOOL_RESPONSE
                                && waiting == WaitReason.QUESTION);
        if (turn.type() == TurnType.AGENT_INIT) {
            Map<String, Object> metadata = new LinkedHashMap<>(turn.payload());
            metadata.put("contextMaxTokens", binding.options().contextWindowOrDefault());
            PromptSource.Rendered source = currentSystemSource;
            if (source != null
                    && !source.sources().isEmpty()
                    && source.text().equals(metadata.get("system_prompt"))) {
                metadata.put(
                        "prompt_source",
                        Map.of(
                                "id",
                                source.id(),
                                "version",
                                2,
                                "message",
                                "system",
                                "spans",
                                source.sources()));
            }
            turn =
                    new TurnRecord(
                            turn.turnNumber(),
                            turn.type(),
                            metadata,
                            turn.timestamp(),
                            turn.llmUsage());
        }
        turn = RecordTokenCounter.unmeasured(turn);
        TurnRecord numbered = journal.append(turn, required);
        turnNumber = numbered.turnNumber();
        events.turn(numbered);
    }

    /**
     * The transparency event for a tool call the agent is about to execute. Domain data only - a
     * transport adapter (the terminal's {@code VetoCommandSender}) maps it to its wire frame. The
     * agent never constructs a client wire type.
     */
    public record ToolCallEvent(@NonNull String toolName, @NonNull Map<String, Object> args) {}

    /**
     * The transparency event for a tool result: the framed observation text the model sees (the
     * self-describing {@code IngressDefense.maskAndFrame} body) plus success.
     */
    public record ToolResultEvent(@NonNull String body, boolean success) {}

    private volatile @NonNull ChatMessage recoveryContext = ChatMessage.user("");

    /** Sets observations only; does not enqueue work or alter durable conversation records. */
    public synchronized void setRecoveredTasks(@NonNull List<RecoveredTask> tasks) {
        recoveryContext = PromptCompiler.compileMessage("runtime-recovery", Map.of("tasks", tasks));
    }

    /**
     * Seeds the runner with replayed history (loaded from the durable turn log on session activate)
     * so a re-activated session resumes its conversation. Must run before the loop processes its
     * first action: {@link VetoAgent}'s ctor starts the virtual thread, but it parks on {@code
     * actionQueue.take()} while IDLE and only touches {@code history} when compiling a submitted
     * prompt - so seeding right after creation, before the first {@code submit}, is safe.
     *
     * <p>Idempotent: a no-op if {@code history} is already non-empty or the replay is empty, so a
     * second get-or-create on an already-live agent does not duplicate turns.
     */
    public synchronized void seedHistory(@NonNull List<TurnRecord> replayed) {
        if (!journal.snapshot().isEmpty() || replayed.isEmpty()) return;
        turnNumber = journal.seed(replayed);
        recoveredWait = RecordRecovery.requiresExplicitContinuation(replayed);
    }

    // ── completion ──────────────────────────────────────────────────────────

    private volatile @NonNull String lastMessage = "";
    private boolean handlingDirectUserPrompt;

    private void completeSuccess() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", turnNumber);
        complete(AgentResult.success(lastMessage, meta));
    }

    private void completeBreaker() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("breakerTrip", true);
        meta.put("turns", turnNumber);
        complete(AgentResult.failure(lastMessage, meta));
    }

    private void completeFailure(String message) {
        completeFailure(message, false, activeRequestId);
    }

    private void completeFailure(String message, boolean cancelled, String request) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("content", message == null ? "" : message);
        if (cancelled) failure.put("outcome", "CANCELLED");
        if (request != null) failure.put("requestId", request);
        appendTurn(new TurnRecord(++turnNumber, TurnType.EXECUTION_ERROR, failure, null));
        // Domain event: the episode failed. Subscribers that surface an error banner use this; the
        // EPISODE_DONE below (success=false) is the authoritative "stop waiting" signal.
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.ERROR)
                        .attr("turnNumber", turnNumber)
                        .text(message == null ? "" : message)
                        .build());
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", turnNumber);
        complete(AgentResult.failure(message == null ? "" : message, meta));
    }

    /**
     * Maps a failed episode's exception to a keyed, session-locale user message. Known types get
     * their own message - LLM timeout / rate-limit / auth / call / parse failures, credential and
     * vault problems, embedder failures, veto refusals; anything else falls back to a generic keyed
     * template with the raw detail as a parameter. The cause chain is walked because retry wrappers
     * ({@code DefaultUniformLLMCaller}) may nest the real failure.
     */
    private @NonNull String failureMessage(@NonNull Throwable e) {
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

    private void complete(@NonNull AgentResult result) {
        Consumer<AgentResult> cb;
        synchronized (this) {
            TaskCancellation task = activeCancellation;
            if (task != null && task.cancelled)
                result = AgentResult.failure("Task cancelled", Map.of());
            rememberRequest();
            waitingForMonitor = false;
            MonitorService monitors = monitorService;
            if (monitors != null) {
                for (var entry : List.copyOf(activatedMonitorEvents.entrySet())) {
                    ActivatedObservation observation = entry.getValue();
                    if (Objects.equals(observation.requestId(), activeRequestId)) {
                        monitors.activationCompleted(
                                agentId, observation.event(), result.success());
                        activatedMonitorEvents.remove(entry.getKey());
                    }
                }
            }
            // Domain event: the episode finished. Carries the authoritative success flag so
            // subscribers
            // (the web UI, the terminal adapter) can stop waiting on the episode without blocking
            // on
            // the
            // submit call. Emitted before the future completes so a subscriber that also awaits the
            // future sees the event first.
            publishFrame(
                    DeltaFrame.builder()
                            .sessionId(sessionId)
                            .kind(DeltaFrame.Kind.EPISODE_DONE)
                            .attr("requestId", activeRequestId == null ? "" : activeRequestId)
                            .attr("turnNumber", turnNumber)
                            .attr("success", result.success())
                            .text(result.message())
                            .build());
            actionQueue.addAll(deferredUserPrompts);
            deferredUserPrompts.clear();
            // Complete the in-place handoff future installed by startTask. Completing the field
            // (rather than reassigning it to a fresh completed future) means an await that already
            // snapshotted resultFuture blocks on the right future and wakes here — a reassignment
            // would leave await holding a stale (already-completed-null) snapshot that returned
            // null.
            if (handlingDirectUserPrompt) return;
            CompletableFuture<AgentResult> completion = monitorResultFuture;
            (completion != null ? completion : task != null ? task.result : resultFuture)
                    .complete(result);
            cb = completion != null ? monitorCallback : task != null ? task.callback : callback;
        }
        if (cb != null) {
            cb.accept(result);
        }
    }

    // ── state + API ops (called by VetoAgent / transport) ────────────────────

    private void transitionTo(@NonNull AgentState next) {
        if (next == AgentState.INTERCEPTED) saveExecutionWait(WaitReason.APPROVAL);
        if (this.state == next) return;
        this.state = next;
        notifyExecutionChanged();
    }

    private void notifyExecutionChanged() {
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.SESSION_INVALIDATED)
                        .attr("agentId", agentId)
                        .attr(
                                "resources",
                                objectMapper.createArrayNode().add("agents").add("execution"))
                        .build());
    }

    public boolean hasPendingWork() {
        if (!sessionAlive || state == AgentState.TERMINATED) return false;
        return state != AgentState.IDLE
                || actionQueue.stream()
                        .anyMatch(
                                action ->
                                        action instanceof AgentAction.UserPromptAction
                                                || action
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction
                                                || action instanceof AgentAction.CompactAction);
    }

    /**
     * Starts a reasoning task: installs a fresh incomplete result future (the authoritative handoff
     * {@link #await} blocks on) and enqueues the action. Installing the future on the caller thread
     * before enqueueing removes the submit→await race — await always snapshots the future this task
     * will complete, not the previous episode's already-completed one.
     */
    public synchronized void startTask(
            Consumer<AgentResult> callback, @NonNull AgentAction action) {
        if (!sessionAlive) throw new IllegalStateException("Agent has terminated");
        if (action instanceof AgentAction.UserPromptAction prompt)
            action = new AgentAction.UserPromptAction(captureUserPrompt(prompt.prompt()));
        this.callback = callback;
        this.resultFuture = new CompletableFuture<>();
        if (action instanceof AgentAction.UserPromptAction) {
            TaskCancellation task = new TaskCancellation(resultFuture, callback);
            taskActions.put(action, task);
            cancellableTasks.put(resultFuture, task);
        }
        if (!sessionAlive) {
            resultFuture.complete(
                    AgentResult.failure(Msg.get(locale, "error.agent.interrupted"), Map.of()));
            return;
        }
        if (this.state == AgentState.INTERCEPTED) {
            hitlRegistry.declineAll(agentId);
        }
        actionQueue.add(action);
        notifyExecutionChanged();
    }

    public void enqueue(@NonNull AgentAction action) {
        if (action instanceof AgentAction.DirectUserPromptAction prompt) {
            synchronized (this) {
                actionQueue.add(
                        new AgentAction.DirectUserPromptAction(captureUserPrompt(prompt.prompt())));
                notifyExecutionChanged();
            }
            return;
        }
        actionQueue.add(action);
        notifyExecutionChanged();
    }

    public void bind(@NonNull LlmBinding binding) {
        this.binding = binding;
    }

    /** The current model binding (the transform stashes this before rebinding to the Leader). */
    public @NonNull LlmBinding binding() {
        return binding;
    }

    /**
     * Subscribes a user-facing-message listener (the emission seam; forwarded in {@link
     * #emitMessage}).
     */
    public void addMessageListener(@NonNull Consumer<String> listener) {
        events.messages.add(listener);
    }

    /** Unsubscribes a user-facing-message listener. */
    public void removeMessageListener(@NonNull Consumer<String> listener) {
        events.messages.remove(listener);
    }

    /**
     * Subscribes an interim-thought listener (the thought emission seam; forwarded in {@link
     * #emitThought}). Fires before the matching message listener because {@link #appendThought}
     * runs before {@link #emitMessage} in the loop.
     */
    public void addThoughtListener(@NonNull Consumer<String> listener) {
        events.thoughts.add(listener);
    }

    /** Unsubscribes an interim-thought listener. */
    public void removeThoughtListener(@NonNull Consumer<String> listener) {
        events.thoughts.remove(listener);
    }

    /**
     * Subscribes a HITL-veto listener (the veto emission seam; forwarded in {@link
     * #emitVetoRequired}). Fires on the agent's virtual thread when a tool call parks for approval.
     */
    public void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        events.vetoes.add(listener);
    }

    /** Unsubscribes a HITL-veto listener. */
    public void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        events.vetoes.remove(listener);
    }

    /**
     * Subscribes a tool-call listener (the transparency emission seam; forwarded in {@link
     * AgentEvents#turn}). Fires on the agent's virtual thread when a TOOL_CALL turn is appended —
     * i.e. immediately before the model receives the tool result for that call.
     */
    public void addToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        events.calls.add(listener);
    }

    /** Unsubscribes a tool-call listener. */
    public void removeToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        events.calls.remove(listener);
    }

    /**
     * Subscribes a tool-result listener (the transparency emission seam; forwarded in {@link
     * AgentEvents#turn}). Fires on the agent's virtual thread when a TOOL_RESPONSE turn is appended
     * — i.e. immediately after the model receives the observation.
     */
    public void addToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        events.results.add(listener);
    }

    /** Unsubscribes a tool-result listener. */
    public void removeToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        events.results.remove(listener);
    }

    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        CompletableFuture<AgentResult> f = resultFuture;
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // The runner completes the future normally via complete (never exceptionally); an
            // exceptional completion here is unexpected — surface its cause.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Agent task completed exceptionally", cause);
        }
    }

    public @NonNull CompletableFuture<AgentResult> result() {
        return resultFuture;
    }

    public @NonNull AgentState state() {
        if (recoveredWait && state != AgentState.TERMINATED) return AgentState.WAITING;
        return state;
    }

    public @NonNull List<TurnRecord> history() {
        return journal.snapshot();
    }

    public @NonNull ReadHistory readHistory() {
        return readHistory;
    }

    /** The whitelisted tool-name view (immutable). */
    public @NonNull Set<String> whitelistedToolsView() {
        return whitelistedTools;
    }

    private String completionTool;
    private boolean completionToolFinished;

    /** Requires a successful call to this registered tool to finish the episode. */
    public void setCompletionTool(@NonNull String toolName) {
        if (!whitelistedTools.contains(toolName))
            throw new IllegalArgumentException("Completion tool must be in the agent's whitelist");
        this.completionTool = toolName;
        this.completionToolFinished = false;
    }

    public @NonNull String agentId() {
        return agentId;
    }

    /** The group this agent belongs to, or null for a single-agent (STANDALONE) loop. */
    public UUID groupId() {
        return groupId;
    }

    /** Stamps the group this agent belongs to (called by group-spawning code / the transform). */
    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public synchronized void restoreLeader(
            @NonNull UUID restoredGroup,
            @NonNull LlmBinding leaderBinding,
            @NonNull Set<ToolDefinition> tools) {
        if (restoredGroup.equals(groupId)) {
            bind(leaderBinding);
            return;
        }
        if (hasPendingWork()) throw new IllegalStateException("Cannot restore a busy Agent");
        preTransformPersona = persona;
        preTransformBinding = binding;
        applyPersona(persona.withRoleAndTools(Role.LEADER, tools));
        bind(leaderBinding);
        setGroupId(restoredGroup);
    }

    /**
     * Stamps the session owner (username) whose model-tier profile resolves this agent's tier.
     * Called by the DB-backed create path ({@link AgentService#createMate} / {@code createAgent})
     * before the loop starts, so group-spawned Mates / Leaders resolve their tier against the
     * user's active profile via the {@link ToolCallContext}.
     */
    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * Stamps the session's message locale (from the request's Accept-Language, resolved by
     * AgentService at submit time); a null locale resets to English. Also propagated to the HITL
     * registry under this agent's id so refusal reasons render in the same locale.
     */
    public void setLocale(Locale locale) {
        this.locale = locale != null ? locale : Locale.ENGLISH;
        hitlRegistry.setLocale(agentId, this.locale);
    }

    /** The session's message locale (see {@link #setLocale}). */
    public @NonNull Locale locale() {
        return locale;
    }

    // Overwrites the default (agent-id-derived) session id with the real session id. Called by the
    // DB-backed create path (AgentService.createAgent) before the loop starts, so persisted turns
    // land under turn_records.session_id = session.getId() and group with their sibling agent
    // streams.
    public void setSessionId(@NonNull UUID sessionId) {
        this.sessionId = sessionId;
        hitlRegistry.setSession(agentId, sessionId);
    }

    public @NonNull AgentPersona personaView() {
        return persona;
    }

    /**
     * Replaces the persona (and its tool-name view) in place. Used by the delegation transform /
     * disband to flip the operational role + tool set without becoming a different agent (the id,
     * session, and user stay; only the role-scoped identity changes). The next {@link #callModel}
     * compiles against the new persona.
     */
    private SessionPlugins sessionPlugins;

    public void attachSessionPlugins(@NonNull SessionPlugins value) {
        sessionPlugins = value;
    }

    public void applyPersona(@NonNull AgentPersona persona) {
        var selection = sessionPlugins;
        if (selection != null)
            persona =
                    persona.withWhitelistedTools(
                            selection.tools(sessionId.toString(), persona.whitelistedTools()));
        this.persona = persona;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        notifyExecutionChanged();
    }

    /**
     * The delegation transform: the calling STANDALONE agent becomes the Leader of a new group. Run
     * on the loop thread inside the tool-call drain pass (after the {@code create_group} tool
     * response is appended), so the transform's REWIND discards this call's response along with the
     * prior standalone turns.
     *
     * <p>Append sequence (each its own turn, monotonic counter): REWIND to 0 (drop the compiled
     * view), AGENT_INIT (the new Leader system message), COMPACTION_SUMMARY (the essence of the
     * prior standalone session, carried forward), USER_PROMPT (the brief). The persona, Leader tool
     * set, top-tier model binding, and group are applied before AGENT_INIT is recorded, so that
     * record describes the agent that will actually receive the next request.
     */
    private void transformToLeader(ToolCallContextHolder.@NonNull TransformDirective directive) {
        String summary = summarizeForRoleChange();

        // Stash the pre-transform STANDALONE persona + binding so disband_group can restore them,
        // then adopt the Leader persona + tool set + top-tier binding + group stamp.
        this.preTransformPersona = this.persona;
        this.preTransformBinding = this.binding;
        applyPersona(persona.withRoleAndTools(Role.LEADER, directive.leaderTools()));
        bind(directive.leaderBinding());
        setGroupId(directive.groupId());

        restartAfterRoleChange(summary, "runtime-leader", directive.brief());
        log.info(
                "Agent {} transformed into Leader of group {} (Leader model={})",
                agentId,
                directive.groupId(),
                directive.leaderBinding().model());
    }

    /**
     * The reverse delegation transform: the Leader becomes STANDALONE again (the group was
     * disbanded). Run on the loop thread inside the tool-call drain pass (after the {@code
     * disband_group} tool response is appended). Append sequence: REWIND to 0, AGENT_INIT (the
     * restored STANDALONE system message), COMPACTION_SUMMARY (the essence of the Leader session),
     * USER_PROMPT (the outcome brief). The stashed STANDALONE persona + binding are restored and
     * the group stamp is cleared before AGENT_INIT is recorded, so the durable definition and the
     * next provider request cannot disagree.
     */
    private void transformToStandalone(@NonNull String brief) {
        String summary = summarizeForRoleChange();

        // Restore the stashed STANDALONE persona + binding. Null-safe: if no transform was stashed
        // (the agent never led a group), flip the role back to STANDALONE on the current persona.
        AgentPersona stashedPersona = preTransformPersona;
        AgentPersona restored =
                stashedPersona != null ? stashedPersona : persona.withRole(Role.STANDALONE);
        LlmBinding stashedBinding = preTransformBinding;
        LlmBinding restoredBinding = stashedBinding != null ? stashedBinding : this.binding;
        applyPersona(restored);
        bind(restoredBinding);
        setGroupId(null);
        this.preTransformPersona = null;
        this.preTransformBinding = null;

        restartAfterRoleChange(summary, "runtime-disband", brief);
        log.info("Agent {} reversed transform back to STANDALONE (group disbanded)", agentId);
    }

    private @NonNull String summarizeForRoleChange() {
        try {
            return computeCompactionSummary(history());
        } catch (RuntimeException error) {
            log.warn(
                    "Agent {} role-change compaction failed; preserving original history",
                    agentId,
                    error);
            return "{}";
        }
    }

    private void restartAfterRoleChange(
            @NonNull String summary, @NonNull String prompt, @NonNull String brief) {
        if (!summary.isBlank() && !"{}".equals(summary)) {
            appendTurn(TurnRecord.rewind(++turnNumber, 0));
            appendAgentInit(linkCurrentSystemMessage());
            appendTurn(TurnRecord.compactionSummary(++turnNumber, summary));
        } else {
            rewindAndRestoreHistory();
        }
        appendTurn(
                PromptCompiler.sourcedUserPrompt(
                        ++turnNumber, prompt, Map.of("task", activeUserTask, "brief", brief)));
        program.reset();
        breaker.newEpisode();
    }

    private volatile Runnable terminationCallback;

    void onTermination(@NonNull Runnable callback) {
        terminationCallback = callback;
    }

    public @NonNull UUID sessionId() {
        return sessionId;
    }

    private void notifyTermination() {
        Runnable callback = terminationCallback;
        if (callback != null) callback.run();
    }

    public void terminate() {
        synchronized (this) {
            sessionAlive = false;
            var events = lifecycleEvents;
            String currentOwner = owner;
            if (events != null && currentOwner != null)
                events.agentTerminated(currentOwner, sessionId.toString(), agentId);
        }
        transitionTo(AgentState.TERMINATED);
        resultFuture.complete(
                AgentResult.failure(Msg.get(locale, "error.agent.interrupted"), Map.of()));
        hitlRegistry.clear(agentId);
        Thread thread = runningThread;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        notifyTermination();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WorkflowHook.@NonNull Context workflowContext() {
        return new WorkflowHook.Context(
                owner, sessionId.toString(), agentId, () -> Thread.currentThread().isInterrupted());
    }

    private <T extends @NonNull Object> @NonNull T workflow(
            @NonNull T initial, SessionPlugins.@NonNull WorkflowOperation<T> operation) {
        var selected = sessionPlugins;
        if (selected == null
                || !selected.has(sessionId.toString(), StandardContributionPoints.WORKFLOW))
            return initial;
        checkTaskCancellation();
        T result = selected.workflow(workflowContext(), initial, operation);
        checkTaskCancellation();
        return result;
    }

    private WorkflowHook.@NonNull Invocation hookInvocation(@NonNull ToolCall call) {
        return new WorkflowHook.Invocation(
                call.toolName(),
                call.callId(),
                PluginJson.object(objectMapper.valueToTree(call.args())));
    }

    private WorkflowHook.@NonNull Decision beforeToolHooks(@NonNull ToolCall call) {
        return workflow(
                WorkflowHook.Decision.CONTINUE,
                (hook, previous) -> {
                    if (previous == WorkflowHook.Decision.REJECT) return previous;
                    var next = hook.beforeTool(workflowContext(), hookInvocation(call));
                    return next.ordinal() > previous.ordinal() ? next : previous;
                });
    }

    private @NonNull VetoResponse callModelWithHooks(@NonNull VetoRequest request) {
        var model = new WorkflowHook.ModelCall(request.providerType().name(), request.modelName());
        workflow(
                model,
                (hook, current) -> {
                    hook.beforeModel(workflowContext(), current);
                    return current;
                });
        VetoResponse response = caller.call(request);
        checkTaskCancellation();
        var transformed =
                workflow(
                        new WorkflowHook.ModelOutput(response.message()),
                        (hook, output) -> hook.afterModel(workflowContext(), model, output));
        return new VetoResponse(
                response.thought(), response.calls(), transformed.message(), response.citations());
    }

    private PluginLifecycleEvents lifecycleEvents;

    public void attachLifecycleEvents(@NonNull PluginLifecycleEvents events) {
        lifecycleEvents = events;
    }

    private synchronized @NonNull String captureUserPrompt(@NonNull String prompt) {
        var selected = sessionPlugins;
        if (selected == null) return prompt;
        String currentOwner = owner;
        if (!sessionAlive || currentOwner == null || currentOwner.isBlank())
            throw new ProtectedInputException();
        try {
            return selected.protect(
                    StandardContributionPoints.INPUT_PROTECTION,
                    new TextProtection.Scope(currentOwner, sessionId.toString(), agentId),
                    prompt);
        } catch (RuntimeException failure) {
            throw new ProtectedInputException();
        }
    }

    /**
     * A model binding: provider/model/credential/options + the Layer-1 system-prompt base. The
     * {@code baseUrl} overrides the provider's default base URL when non-null (per-user, per-tier);
     * null falls back to the provider strategy's default.
     */
    public record LlmBinding(
            @NonNull ProviderType provider,
            @NonNull String model,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            String systemPromptBase,
            String baseUrl) {

        /**
         * Convenience constructor for callers that do not override the base URL (null -> default).
         */
        public LlmBinding(
                @NonNull ProviderType provider,
                @NonNull String model,
                @NonNull String credentialKey,
                @NonNull LlmOptions options,
                String systemPromptBase) {
            this(provider, model, credentialKey, options, systemPromptBase, null);
        }
    }

    /** Signals a breaker trip (caught at the action boundary → IDLE + notice). */
    private static final class BreakerTripException extends RuntimeException {}

    /**
     * Signals a batch abort after a policy-refusal / user-decline (caught at the action boundary →
     * completeFailure). Carries no message; the failure seam maps the type to the keyed, localized
     * "veto refused" message.
     */
    private static final class VetoRefusedException extends RuntimeException {
        private final boolean approvalRequested;

        private VetoRefusedException() {
            this(false);
        }

        private VetoRefusedException(boolean approvalRequested) {
            this.approvalRequested = approvalRequested;
        }
    }
}
