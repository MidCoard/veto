package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentEventSink;
import top.focess.veto.agent.AgentExecutionPolicy;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.ToolExecutionBoundary;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.event.EventManager;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierConfigException;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

/** Host assembly and lifetime of a private invocation child; no feature policy or result parser. */
@Component
public final class IsolatedExecutions {

    private static final @NonNull
            ConcurrentHashMap<@NonNull ToolCallContext, @NonNull IsolatedInvocationState>
            INVOCATIONS = new ConcurrentHashMap<>();
    private static final @NonNull
            ConcurrentHashMap<@NonNull String, @NonNull IsolatedExecutionContext>
            PRIVATE_CONTEXTS = new ConcurrentHashMap<>();

    /**
     * Throws when the given context belongs to an isolated execution rather than a real parent
     * invocation.
     */
    public static void requireNonIsolatedParent(@NonNull ToolCallContext context) {
        if (PRIVATE_CONTEXTS.containsKey(context.agentId()))
            throw new SecurityException(
                    "Isolated execution requires an explicitly delegated destination");
    }

    /** Closes the isolated child, if any, that the given tool invocation opened. */
    public static void finishInvocation(ToolCallContext context) {
        if (context == null) return;
        var invocation = INVOCATIONS.get(context);
        if (invocation != null) {
            var child = invocation.child;
            if (child != null) child.close();
        }
    }

    /** Finishes the invocation's isolated child, if any, and forgets its bookkeeping. */
    public static void releaseInvocation(ToolCallContext context) {
        try {
            finishInvocation(context);
        } finally {
            if (context != null) INVOCATIONS.remove(context);
        }
    }

    private final @NonNull ObjectMapper mapper;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull ModelTierRegistry models;
    private final @NonNull CapabilityTranslator translator;
    private final @NonNull SessionAgentRegistry registry;
    private final @NonNull TurnLogService history;
    private final @NonNull IngressDefense ingress;
    private final @NonNull SessionPlugins sessionPlugins;
    private final @NonNull EventManager events;
    private final @NonNull SessionActions invalidations;
    private final int callCeiling;
    private final int secondsCeiling;
    private final int inputCeiling;
    private final int outputCeiling;

    /** Creates the engine with operator-configured ceilings; every ceiling must be positive. */
    public IsolatedExecutions(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull UniformLLMCaller caller,
            @NonNull ModelTierRegistry models,
            @NonNull CapabilityTranslator translator,
            @NonNull SessionAgentRegistry registry,
            @NonNull TurnLogService history,
            @NonNull IngressDefense ingress,
            @Value("${veto.isolated.max-calls:128}") int callCeiling,
            @Value("${veto.isolated.max-seconds:600}") int secondsCeiling,
            @Value("${veto.isolated.max-input-tokens:1048576}") int inputCeiling,
            @Value("${veto.isolated.max-output-tokens:65536}") int outputCeiling,
            @NonNull SessionPlugins sessionPlugins,
            @NonNull EventManager events,
            @NonNull SessionActions invalidations) {
        this.mapper = mapper;
        this.caller = caller;
        this.models = models;
        this.translator = translator;
        this.registry = registry;
        this.history = history;
        this.ingress = ingress;
        this.callCeiling = callCeiling;
        this.secondsCeiling = secondsCeiling;
        this.inputCeiling = inputCeiling;
        this.outputCeiling = outputCeiling;
        this.sessionPlugins = sessionPlugins;
        this.events = events;
        this.invalidations = invalidations;
        if (callCeiling < 1 || secondsCeiling < 1 || inputCeiling < 1 || outputCeiling < 1)
            throw new IllegalArgumentException("Invalid isolated execution ceiling");
    }

    /**
     * Opens an isolated child for the current authorized parent invocation, clamped to host
     * ceilings. The {@code admitted} supplier must stay true for the child's whole lifetime.
     */
    public @NonNull IsolatedChildAgent open(
            IsolatedAgent.@NonNull Spec spec,
            IsolatedAgent.@NonNull Factory factory,
            @NonNull BooleanSupplier admitted) {
        var current = ToolCallContextHolder.get();
        if (current == null) throw new SecurityException("No authorized parent invocation");
        var parent = CapabilityAccess.require(current.executionPermit().capability());
        UUID userId = parent.userId();
        UUID session = parent.sessionId();
        if (userId == null || session == null || !admitted.getAsBoolean())
            throw new SecurityException("No authenticated parent session");
        var invocation = new IsolatedInvocationState();
        if (INVOCATIONS.putIfAbsent(parent, invocation) != null)
            throw new SecurityException("This call already opened an isolated execution");
        ModelBinding model = resolve(userId, spec.tiers());
        var limits = limits(spec);
        if (spec.terminal().reservedCalls() >= limits.calls())
            throw new IllegalArgumentException("No nonterminal call budget");
        var scope =
                new IsolatedExecutionContext(
                        parent,
                        Thread.currentThread(),
                        admitted,
                        UUID.randomUUID().toString(),
                        model.model(),
                        limits,
                        spec.terminal());
        var tools = factory.open(scope);
        scope.checkTools = tools::check;
        PRIVATE_CONTEXTS.put(scope.id(), scope);
        try {
            var engine = ToolEngineImpl.isolated(mapper, tools.tools(), sessionPlugins, events);
            if (engine.getActiveTools(null).stream()
                    .anyMatch(tool -> tool.capability() != parent.executionPermit().capability()))
                throw new SecurityException(
                        "Private tool capability exceeds its parent invocation");
            scope.privateTools =
                    engine.getActiveTools(null).stream()
                            .map(tool -> tool.name())
                            .collect(Collectors.toSet());
            var destination = scope.destination;
            if (destination != null) destination.requireOperation(scope.privateTools);
            var persona =
                    new AgentPersona(
                            scope.id(),
                            spec.name(),
                            render(spec.description()),
                            Set.copyOf(engine.getActiveTools(null)));
            var gateway =
                    new Gateway(
                            Workspace.fromConfig("", "", "REAL"),
                            new DangerComputation(),
                            SlmScreeningProvider.unavailable(),
                            parent.executionPermit().deployerPolicy(),
                            new ProtectedSet(parent.executionPermit().protectedPaths()),
                            new ReadHistory(),
                            permit ->
                                    permit.withAccessScope(
                                            parent.executionPermit().accessRoots(),
                                            parent.executionPermit().occupiedRoots()));
            var options =
                    new LlmOptions(
                            model.temperature(),
                            null,
                            Math.min(limits.outputTokens(), model.maxOutputTokens()),
                            limits.timeout(),
                            model.contextWindowTokens());
            int inputBudget = (int) Math.min(limits.inputTokens(), options.inputBudget());
            var compiler =
                    PromptCompiler.isolated(translator, mapper, render(spec.system()), inputBudget);
            UniformLLMCaller measured =
                    (request, ignoredSessionId) -> {
                        scope.check();
                        int overhead =
                                bytes(request.systemPrompt())
                                        + bytes(request.userPrompt())
                                        + bytes(
                                                json(
                                                        request.tools().stream()
                                                                .map(ToolDefinition::wireView)
                                                                .toList()))
                                        + limits.framingReserveBytes();
                        scope.observationBudget = Math.max(0, inputBudget - overhead);
                        scope.calls.incrementAndGet();
                        var remaining =
                                new LlmOptions(
                                        options.temperature(),
                                        options.topP(),
                                        options.maxTokens(),
                                        Duration.ofNanos(
                                                Math.max(1, scope.deadline - System.nanoTime())),
                                        options.contextWindowTokens());
                        var bounded =
                                new VetoRequest(
                                        request.systemPrompt(),
                                        request.userPrompt(),
                                        request.tools(),
                                        request.providerType(),
                                        request.modelName(),
                                        request.credentialKey(),
                                        remaining,
                                        request.messages(),
                                        request.baseUrl(),
                                        request.nativeToolsEnabled(),
                                        request.responseContract());
                        try {
                            // Provider resolution uses the host-authorized parent session.
                            return caller.call(bounded, session.toString());
                        } finally {
                            for (var usage : LlmSystemUsage.snapshot()) {
                                scope.input.addAndGet(usage.promptTokens());
                                scope.output.addAndGet(usage.completionTokens());
                            }
                        }
                    };
            var runner =
                    new AgentRunner(
                            scope.id(),
                            persona,
                            engine,
                            new ToolExecutionBoundary(
                                    scope.id(),
                                    session,
                                    userId,
                                    engine,
                                    gateway,
                                    new HitlRegistry(null, invalidations),
                                    ingress),
                            List.of(),
                            compiler,
                            measured,
                            mapper,
                            limits.calls(),
                            new LlmBinding(
                                    model.provider(),
                                    model.model(),
                                    model.credentialKey(),
                                    options,
                                    model.baseUrl()),
                            AgentEventSink.none(),
                            parent.userId(),
                            history,
                            session);
            runner.setExecutionPolicy(new AgentExecutionPolicy(spec.terminal(), scope::check));
            var agent =
                    registry.startIsolated(
                            session,
                            parent.agentId(),
                            parent.executionPermit().callId(),
                            persona,
                            runner);
            var child =
                    new IsolatedChildAgent(
                            scope,
                            tools,
                            agent,
                            runner,
                            () -> PRIVATE_CONTEXTS.remove(scope.id(), scope));
            invocation.child = child;
            return child;
        } catch (RuntimeException error) {
            scope.closed = true;
            PRIVATE_CONTEXTS.remove(scope.id(), scope);
            tools.close();
            throw error;
        }
    }

    private IsolatedAgent.@NonNull Limits limits(IsolatedAgent.@NonNull Spec spec) {
        var requested = spec.limits();
        return new IsolatedAgent.Limits(
                Math.min(requested.calls(), callCeiling),
                requested.timeout().compareTo(Duration.ofSeconds(secondsCeiling)) < 0
                        ? requested.timeout()
                        : Duration.ofSeconds(secondsCeiling),
                Math.min(requested.inputTokens(), inputCeiling),
                Math.min(requested.outputTokens(), outputCeiling),
                requested.framingReserveBytes());
    }

    private @NonNull String render(AgentProfile.@NonNull Prompt prompt) {
        return PromptCompiler.compileDocument(prompt.resource(), JsonValues.toMap(prompt.data()))
                .text();
    }

    private @NonNull String json(Object value) {
        if (value == null) return "null";
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot measure model input", error);
        }
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private @NonNull ModelBinding resolve(
            @NonNull UUID userId, @NonNull List<@NonNull String> tiers) {
        ModelTierConfigException failure = null;
        for (String tier : tiers) {
            try {
                return models.resolve(userId, Nullness.requireNonNull(ModelTier.valueOf(tier)));
            } catch (ModelTierConfigException error) {
                failure = error;
            }
        }
        if (failure != null) throw failure;
        throw new IllegalArgumentException("No model tiers");
    }

    /** Parent invocation bookkeeping; child publication is visible to cancellation and release. */
    private static final class IsolatedInvocationState {
        private volatile IsolatedChildAgent child;
    }
}
