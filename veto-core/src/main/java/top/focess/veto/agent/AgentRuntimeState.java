package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Per-runner host state. All task synchronization uses this single object. */
final class AgentRuntimeState {
    static final @NonNull Logger log = LoggerFactory.getLogger("top.focess.veto.agent.AgentRunner");

    final @NonNull String agentId;

    volatile @NonNull AgentPersona persona;

    volatile @NonNull Set<String> whitelistedTools;

    final @NonNull ToolEngine toolEngine;

    final @NonNull ModelResponseValidation responses;

    final @NonNull Gateway gateway;

    final @NonNull HitlRegistry hitlRegistry;

    final @NonNull IngressDefense ingressDefense;

    final @NonNull List<LoopInterceptor> interceptors;

    final @NonNull PromptCompiler promptCompiler;

    @NonNull ToolResultPresentationMode toolResultPresentation = ToolResultPresentationMode.BASIC;

    final @NonNull UniformLLMCaller caller;

    final @NonNull ObjectMapper objectMapper;

    final long maxCallsPerEpisode;

    final @NonNull ReadHistory readHistory;

    final @NonNull UUID sessionId;

    final @NonNull UUID userId;

    final String owner;

    volatile @NonNull Locale locale = Locale.ENGLISH;

    volatile @NonNull LlmBinding binding;

    final @NonNull AgentPersona basePersona;

    volatile @NonNull LlmBinding baseBinding;

    long configurationRevision;
    String configurationTransition;

    final @NonNull BlockingQueue<QueuedRequest> actionQueue = new LinkedBlockingQueue<>();

    final @NonNull List<QueuedRequest> deferredUserPrompts = new ArrayList<>();

    Consumer<RequestHandle> backgroundRequestListener;

    volatile @NonNull ExecutionControl control = new ExecutionControl.Idle();

    KeysteadVault executionVault;

    ModelTierRegistry modelTierRegistry;

    AgentWorkSource workSource;

    final @NonNull Map<String, ActivatedObservation> activatedObservations = new LinkedHashMap<>();

    record ActivatedObservation(AgentWorkSource.@NonNull Observation event, String requestId) {}

    RequestContinuationStore continuationStore;

    final @NonNull AtomicBoolean workQueued = new AtomicBoolean();

    volatile Thread runningThread;

    record ResolvedCall(@NonNull ToolCall call, @NonNull ToolExecutionPermit executionPermit) {}

    @NonNull AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.ordinary();

    @NonNull String currentTask() {
        RequestHandle request = control.request();
        return request == null ? "" : request.episode.task();
    }

    SessionPlugins sessionPlugins;

    volatile Runnable terminationCallback;
    boolean terminationNotified;

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
            String owner,
            @NonNull UUID sessionId) {
        this.agentId = agentId;
        this.persona = persona;
        this.basePersona = persona;
        this.baseBinding = binding;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        this.toolEngine = toolEngine;
        this.responses = new ModelResponseValidation(toolEngine, objectMapper);
        this.gateway = gateway;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        this.readHistory = gateway.readHistory();
        this.binding = binding;
        // agentId is the persona id (a UUID string — see AgentService.createAgent); derive the
        // per-session frame key once. Fail-fast if a non-UUID id ever reaches here.
        this.sessionId = sessionId;
        this.owner = owner;
        hitlRegistry.setSession(agentId, this.sessionId);
        this.userId = userId;
        this.output =
                new AgentOutput(
                        new AgentHistory(turnLogService, () -> sessionId, userId, agentId),
                        new AgentEvents(agentId, objectMapper, deltaBroker, () -> sessionId),
                        promptCompiler,
                        new ToolResultPresenter(objectMapper),
                        toolEngine,
                        () ->
                                new AgentOutput.View(
                                        control.request(),
                                        toolResultPresentation,
                                        control.waiting(ExecutionControl.Wait.QUESTION),
                                        this.binding.options().contextWindowOrDefault()));
    }

    private AgentToolExecution tools;

    @NonNull AgentToolExecution tools() {
        return Nullness.requireNonNull(tools, "Runtime is not initialized");
    }

    private ModelSession models;

    @NonNull ModelSession models() {
        return Nullness.requireNonNull(models, "Runtime is not initialized");
    }

    private AgentContinuationExecution continuations;

    @NonNull AgentContinuationExecution continuations() {
        return Nullness.requireNonNull(continuations, "Runtime is not initialized");
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
        continuations = new AgentContinuationExecution(this);
        lifecycle = new AgentLifecycle(this);
        hooks =
                new AgentPluginHooks(
                        new WorkflowHook.Context(
                                owner,
                                sessionId.toString(),
                                agentId,
                                () -> Thread.currentThread().isInterrupted()),
                        objectMapper,
                        caller,
                        () -> sessionPlugins,
                        () -> control.open(),
                        () ->
                                control.request() != null
                                        && Nullness.requireNonNull(control.request()).cancelled);
        models =
                new ModelSession(
                        agentId,
                        promptCompiler,
                        responses,
                        toolEngine,
                        output(),
                        hooks(),
                        () -> lifecycle().currentRequest().episode.breaker(),
                        () ->
                                new ModelSession.Configuration(
                                        persona,
                                        binding,
                                        toolResultPresentation,
                                        owner,
                                        modelTierRegistry,
                                        executionPolicy.terminal(),
                                        gateway.workspace()),
                        () -> lifecycle().checkExecutionBoundary(),
                        () -> continuations().reserveRequestCall(),
                        () -> lifecycle().tripBreaker());
    }
}
