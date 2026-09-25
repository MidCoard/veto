package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentEventSink;
import top.focess.veto.agent.AgentExecutionPolicy;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.ToolExecutionBoundary;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.HttpDestinationGrant;
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
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValues;
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
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class IsolatedExecutions {
    private static final class Invocation {
        private volatile @Nullable Child child;
    }

    private static final ConcurrentHashMap<ToolCallContext, Invocation> INVOCATIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Scope> PRIVATE_CONTEXTS =
            new ConcurrentHashMap<>();

    public static void requireNonIsolatedParent(ToolCallContext context) {
        if (PRIVATE_CONTEXTS.containsKey(context.agentId()))
            throw new SecurityException(
                    "Isolated execution requires an explicitly delegated destination");
    }

    public static void finishInvocation(@Nullable ToolCallContext context) {
        if (context == null) return;
        var invocation = INVOCATIONS.get(context);
        if (invocation != null) {
            var child = invocation.child;
            if (child != null) child.close();
        }
    }

    public static void releaseInvocation(@Nullable ToolCallContext context) {
        try {
            finishInvocation(context);
        } finally {
            if (context != null) INVOCATIONS.remove(context);
        }
    }

    private final ObjectMapper mapper;
    private final UniformLLMCaller caller;
    private final ModelTierRegistry models;
    private final CapabilityTranslator translator;
    private final SessionAgentRegistry registry;
    private final TurnLogService history;
    private final IngressDefense ingress;
    private final int callCeiling;
    private final int secondsCeiling;
    private final int inputCeiling;
    private final int outputCeiling;

    public IsolatedExecutions(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper mapper,
            UniformLLMCaller caller,
            ModelTierRegistry models,
            CapabilityTranslator translator,
            SessionAgentRegistry registry,
            TurnLogService history,
            IngressDefense ingress,
            @Value("${veto.isolated.max-calls:128}") int callCeiling,
            @Value("${veto.isolated.max-seconds:600}") int secondsCeiling,
            @Value("${veto.isolated.max-input-tokens:1048576}") int inputCeiling,
            @Value("${veto.isolated.max-output-tokens:65536}") int outputCeiling) {
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
        if (callCeiling < 1 || secondsCeiling < 1 || inputCeiling < 1 || outputCeiling < 1)
            throw new IllegalArgumentException("Invalid isolated execution ceiling");
    }

    public Child open(
            IsolatedAgent.Spec spec, IsolatedAgent.Factory factory, BooleanSupplier admitted) {
        var current = ToolCallContextHolder.get();
        if (current == null) throw new SecurityException("No authorized parent invocation");
        var parent = CapabilityAccess.require(current.executionPermit().capability());
        String owner = parent.owner();
        UUID session = parent.sessionId();
        if (owner == null || session == null || !admitted.getAsBoolean())
            throw new SecurityException("No authenticated parent session");
        var invocation = new Invocation();
        if (INVOCATIONS.putIfAbsent(parent, invocation) != null)
            throw new SecurityException("This call already opened an isolated execution");
        ModelBinding model = resolve(owner, spec.tiers());
        var limits = limits(spec);
        if (spec.terminal().reservedCalls() >= limits.calls())
            throw new IllegalArgumentException("No nonterminal call budget");
        var scope =
                new Scope(
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
            var engine = ToolEngineImpl.isolated(mapper, tools.tools());
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
                            new ReadHistory());
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
                    request -> {
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
                            return caller.call(bounded);
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
                                    owner,
                                    engine,
                                    gateway,
                                    new HitlRegistry(),
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
                            owner,
                            session);
            runner.setExecutionPolicy(new AgentExecutionPolicy(spec.terminal(), scope::check));
            var agent =
                    registry.startIsolated(
                            session,
                            parent.agentId(),
                            parent.executionPermit().callId(),
                            persona,
                            runner);
            var child = new Child(scope, tools, agent, runner);
            invocation.child = child;
            return child;
        } catch (RuntimeException error) {
            scope.closed = true;
            PRIVATE_CONTEXTS.remove(scope.id(), scope);
            tools.close();
            throw error;
        }
    }

    private IsolatedAgent.Limits limits(IsolatedAgent.Spec spec) {
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

    private String render(AgentProfile.Prompt prompt) {
        return PromptCompiler.compileDocument(prompt.resource(), JsonValues.toMap(prompt.data()))
                .text();
    }

    private String json(@Nullable Object value) {
        if (value == null) return "null";
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot measure model input", error);
        }
    }

    private static int bytes(@Nullable String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private ModelBinding resolve(String owner, List<String> tiers) {
        ModelTierConfigException failure = null;
        for (String tier : tiers) {
            try {
                return models.resolve(owner, Nullness.requireNonNull(ModelTier.valueOf(tier)));
            } catch (ModelTierConfigException error) {
                failure = error;
            }
        }
        if (failure != null) throw failure;
        throw new IllegalArgumentException("No model tiers");
    }

    /** Host-issued contract context; API-only plugins cannot construct it. */
    public static final class Scope implements IsolatedAgent.Runtime {
        private final ToolCallContext parent;
        private final Thread parentThread;
        private final BooleanSupplier admitted;
        private final String id;
        private final String model;
        private final long started = System.nanoTime();
        private final long deadline;
        private final IsolatedAgent.Terminal terminal;
        private final IsolatedAgent.Limits limits;
        private Set<String> privateTools = Set.of();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong input = new AtomicLong(), output = new AtomicLong();
        private volatile int observationBudget;
        private Runnable checkTools = () -> {};
        private volatile boolean closed;
        private volatile boolean completed;
        private @Nullable HttpDestinationGrant destination;

        private Scope(
                ToolCallContext parent,
                Thread parentThread,
                BooleanSupplier admitted,
                String id,
                String model,
                IsolatedAgent.Limits limits,
                IsolatedAgent.Terminal terminal) {
            this.parent = parent;
            this.parentThread = parentThread;
            this.admitted = admitted;
            this.id = id;
            this.model = model;
            this.deadline = started + limits.timeout().toNanos();
            this.terminal = terminal;
            this.limits = limits;
        }

        public String id() {
            return id;
        }

        public long deadline() {
            return deadline;
        }

        public ToolCallContext parent() {
            return parent;
        }

        public void bind(HttpDestinationGrant grant) {
            authorizeParent();
            if (destination != null) throw new SecurityException("Destination already bound");
            destination = grant;
        }

        public void authorizeParent() {
            var context = ToolCallContextHolder.get();
            if (context == null
                    || !parent.equals(
                            CapabilityAccess.require(context.executionPermit().capability())))
                throw new SecurityException("Parent invocation changed");
            if (parentThread.isInterrupted() || !admitted.getAsBoolean())
                throw new CancellationException("Parent invocation cancelled");
        }

        public void check() {
            checkTools.run();
            if (closed
                    || Thread.currentThread().isInterrupted()
                    || parentThread.isInterrupted()
                    || !admitted.getAsBoolean())
                throw new CancellationException("Isolated execution cancelled");
            if (System.nanoTime() >= deadline)
                throw new CancellationException("Isolated execution deadline exceeded");
        }

        public void authorize(String operation) {
            if (!privateTools.contains(operation))
                throw new SecurityException("Operation is not a private tool");
            var context = ToolCallContextHolder.get();
            if (context == null) throw new SecurityException("No private tool invocation");
            CapabilityAccess.require(parent.executionPermit().capability(), operation);
            if (!id.equals(context.agentId())
                    || !parent.userId().equals(context.userId())
                    || !Objects.equals(parent.owner(), context.owner())
                    || !Objects.equals(parent.sessionId(), context.sessionId()))
                throw new SecurityException("Private tool belongs to another execution");
            check();
        }

        public boolean provenanceLive() {
            return !parentThread.isInterrupted()
                    && admitted.getAsBoolean()
                    && System.nanoTime() < deadline;
        }

        public int observationBudgetBytes() {
            return observationBudget;
        }

        public IsolatedAgent.Usage usage() {
            return new IsolatedAgent.Usage(
                    id,
                    model,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    calls.get(),
                    input.get(),
                    output.get());
        }

        public void complete(String result) {
            authorize(terminal.tool());
            completed = true;
            ToolCallContextHolder.finish(result);
        }
    }

    public static final class Child implements IsolatedAgent {
        private final Scope scope;
        private final IsolatedAgent.Tools tools;
        private final VetoAgent agent;
        private final AgentRunner runner;
        private @Nullable RequestHandle request;
        private volatile boolean closed;
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final AtomicLong terminationDeadline = new AtomicLong();
        private Runnable onClosed = () -> {};

        public synchronized void onClosed(Runnable callback) {
            if (closed) callback.run();
            else onClosed = callback;
        }

        private Child(Scope scope, IsolatedAgent.Tools tools, VetoAgent agent, AgentRunner runner) {
            this.scope = scope;
            this.tools = tools;
            this.agent = agent;
            this.runner = runner;
            agent.onTermination(
                    () ->
                            Thread.ofVirtual()
                                    .name("isolated-cleanup-" + scope.id())
                                    .start(
                                            () -> {
                                                boolean interrupted = false;
                                                try {
                                                    while (true) {
                                                        try {
                                                            if (agent.awaitTermination(
                                                                    Duration.ofSeconds(1))) break;
                                                        } catch (InterruptedException stopped) {
                                                            interrupted = true;
                                                        }
                                                    }
                                                    cleanup();
                                                } finally {
                                                    if (interrupted)
                                                        Thread.currentThread().interrupt();
                                                }
                                            }));
        }

        public Scope scope() {
            return scope;
        }

        public String id() {
            return agent.id();
        }

        public AgentState state() {
            return agent.state();
        }

        public IsolatedAgent.Limits limits() {
            return scope.limits;
        }

        public IsolatedAgent.Usage usage() {
            return scope.usage();
        }

        public boolean budgetExhausted() {
            return runner.budgetExhausted();
        }

        public synchronized AgentHost.Request submit(String prompt) {
            scope.authorizeParent();
            scope.check();
            if (request != null)
                throw new IllegalStateException("Isolated child accepts one request");
            var handle = agent.submitRequest(prompt);
            request = handle;
            return new AgentHost.Request() {
                public String id() {
                    return handle.requestId();
                }

                public CompletableFuture<AgentResult> result() {
                    return handle.result().copy();
                }

                public CompletableFuture<Boolean> settled() {
                    return handle.settled().copy();
                }

                public boolean cancel(Duration timeout) throws InterruptedException {
                    return agent.cancelTask(handle.result(), timeout);
                }
            };
        }

        public boolean settledSuccessfully() {
            var active = request;
            return closed
                    && scope.completed
                    && active != null
                    && active.settled().isDone()
                    && active.result().isDone()
                    && active.result().join().success();
        }

        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            return agent.awaitTermination(timeout);
        }

        private synchronized void cleanup() {
            if (closed) return;
            scope.closed = true;
            closed = true;
            PRIVATE_CONTEXTS.remove(scope.id(), scope);
            try {
                tools.close();
            } finally {
                onClosed.run();
            }
        }

        public void close() {
            if (closed) return;
            scope.closed = true;
            long deadline =
                    terminationDeadline.updateAndGet(
                            existing ->
                                    existing == 0
                                            ? System.nanoTime() + Duration.ofSeconds(2).toNanos()
                                            : existing);
            if (closeRequested.compareAndSet(false, true)) agent.terminate();
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0)
                        throw new IllegalStateException(
                                "Isolated execution did not terminate within the close deadline;"
                                        + " cleanup remains pending");
                    try {
                        if (agent.awaitTermination(Duration.ofNanos(remaining))) {
                            cleanup();
                            return;
                        }
                    } catch (InterruptedException stopped) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
