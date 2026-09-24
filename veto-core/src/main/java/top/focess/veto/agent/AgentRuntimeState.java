package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.ToolExecutionPermit.TaskBinding;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.workflow.PlanExecution;
import top.focess.veto.api.agent.workflow.PlanStepContext;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.monitor.RequestContinuationStore;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Per-runner host state. All task synchronization uses this single object. */
final class AgentRuntimeState {
    static final @NonNull Logger log = LoggerFactory.getLogger("top.focess.veto.agent.AgentRunner");

    final @NonNull String agentId;

    volatile @NonNull AgentPersona persona;

    volatile @NonNull Set<String> whitelistedTools;

    final @NonNull ToolEngine toolEngine;

    final @NonNull ResponseValidator responses;

    final @NonNull Gateway gateway;

    final @NonNull HitlRegistry hitlRegistry;

    final @NonNull IngressDefense ingressDefense;

    final @NonNull List<LoopInterceptor> interceptors;

    final @NonNull PromptCompiler promptCompiler;

    @NonNull ToolResultPresentationMode toolResultPresentation = ToolResultPresentationMode.BASIC;

    final @NonNull UniformLLMCaller caller;

    final @NonNull ObjectMapper objectMapper;

    final @NonNull ToolResultPresenter toolResultPresenter;

    final @NonNull LoopBreaker breaker;

    final @NonNull ReadHistory readHistory;

    final @NonNull AgentEvents events;

    volatile @NonNull UUID sessionId;

    final @NonNull AgentHistory journal;

    final @NonNull UUID userId;

    final BackgroundTaskManager backgroundTaskManager;

    volatile String owner;

    volatile @NonNull Locale locale = Locale.ENGLISH;

    volatile UUID groupId;

    volatile @NonNull LlmBinding binding;

    volatile AgentPersona preTransformPersona;

    volatile LlmBinding preTransformBinding;

    final @NonNull BlockingQueue<AgentAction> actionQueue = new LinkedBlockingQueue<>();

    final @NonNull List<AgentAction.@NonNull DirectUserPromptAction> deferredUserPrompts =
            new ArrayList<>();

    volatile @NonNull AgentState state = AgentState.IDLE;

    KeysteadVault monitorVault;

    enum WaitReason {
        APPROVAL,
        QUESTION,
        BREAKER
    }

    volatile WaitReason executionWait;

    volatile boolean recoveredWait;

    int turnNumber = 0;

    PlanExecution program;

    int maxPlanSteps;

    ModelTierRegistry planTierRegistry;

    volatile @NonNull CompletableFuture<AgentResult> resultFuture = new CompletableFuture<>();

    Consumer<AgentResult> callback;

    final @NonNull Map<AgentAction, TaskCancellation> taskActions = new IdentityHashMap<>();

    final @NonNull Map<CompletableFuture<AgentResult>, TaskCancellation> cancellableTasks =
            new HashMap<>();

    volatile TaskCancellation activeCancellation;

    CompletableFuture<AgentResult> lastExitedTask;

    static final class TaskCancellation {
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

    volatile boolean sessionAlive = true;

    double correctionFactor = 1.0;

    volatile PluginContextSnapshot lastPluginContext;

    boolean awaitingBreakerContinuation = false;

    CompiledPrompt preparedFirstPrompt = null;

    @NonNull String activeUserTask = "";

    final @NonNull Set<String> declinedCallSignatures = new HashSet<>();

    MonitorService monitorService;

    volatile boolean waitingForMonitor;

    String activeRequestId;

    String activeMonitorEventId;

    final @NonNull Map<String, RequestContinuation> requestContinuations = new HashMap<>();

    CompletableFuture<AgentResult> monitorResultFuture;

    Consumer<AgentResult> monitorCallback;

    final @NonNull Map<String, ActivatedObservation> activatedMonitorEvents = new LinkedHashMap<>();

    record ActivatedObservation(MonitorRecord.@NonNull Event event, String requestId) {}

    record RequestContinuation(@NonNull String task, long consumedCalls) {}

    RequestContinuationStore continuationStore;

    final @NonNull AtomicBoolean monitorQueued = new AtomicBoolean();

    volatile Thread runningThread;

    VetoRequest submissionRequest;

    boolean submissionGeneration;

    ToolCallContextHolder.ResponseDirective pendingResponse;

    PlanStepContext currentPlanStep;

    PromptSource.Rendered currentSystemSource;

    final @NonNull Map<String, ApprovalReceipt> approvalReceipts = new HashMap<>();

    record ResolvedCall(@NonNull ToolCall call, @NonNull ToolExecutionPermit executionPermit) {}

    record ProcessInputTarget(@NonNull TaskBinding binding, @NonNull String screeningContext) {}

    MessageCitations.Bound lastCitations;

    String lastModelCallId;

    String currentToolModelCallId;

    volatile @NonNull ChatMessage recoveryContext = ChatMessage.user("");

    volatile @NonNull String lastMessage = "";

    boolean handlingDirectUserPrompt;

    String completionTool;

    boolean completionToolFinished;

    SessionPlugins sessionPlugins;

    volatile Runnable terminationCallback;

    PluginLifecycleEvents lifecycleEvents;

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

    AgentRuntimeState(
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
        // agentId is the persona id (a UUID string — see AgentService.createAgent); derive the
        // per-session frame key once. Fail-fast if a non-UUID id ever reaches here.
        this.sessionId = UUID.fromString(agentId);
        this.events = new AgentEvents(agentId, objectMapper, deltaBroker, () -> sessionId);
        hitlRegistry.setSession(agentId, this.sessionId);
        this.userId = userId;
        this.journal = new AgentHistory(turnLogService, () -> sessionId, userId, agentId);
        this.backgroundTaskManager = backgroundTaskManager;
    }

    private AgentToolExecution tools;

    @NonNull AgentToolExecution tools() {
        return Nullness.requireNonNull(tools, "Runtime is not initialized");
    }

    private AgentModelExecution models;

    @NonNull AgentModelExecution models() {
        return Nullness.requireNonNull(models, "Runtime is not initialized");
    }

    private AgentMonitorExecution monitor;

    @NonNull AgentMonitorExecution monitor() {
        return Nullness.requireNonNull(monitor, "Runtime is not initialized");
    }

    private AgentOutput output;

    @NonNull AgentOutput output() {
        return Nullness.requireNonNull(output, "Runtime is not initialized");
    }

    private AgentLifecycle lifecycle;

    @NonNull AgentLifecycle lifecycle() {
        return Nullness.requireNonNull(lifecycle, "Runtime is not initialized");
    }

    private AgentPluginHooks hooks;

    @NonNull AgentPluginHooks hooks() {
        return Nullness.requireNonNull(hooks, "Runtime is not initialized");
    }

    void initializeComponents() {
        tools = new AgentToolExecution(this);
        models = new AgentModelExecution(this);
        monitor = new AgentMonitorExecution(this);
        output = new AgentOutput(this);
        lifecycle = new AgentLifecycle(this);
        hooks = new AgentPluginHooks(this);
    }
}
