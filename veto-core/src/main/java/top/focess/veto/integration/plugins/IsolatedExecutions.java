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
import org.jspecify.annotations.NonNull;
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
public final class IsolatedExecutions {
    private static final class Invocation {
        private volatile Child child;
    }

    private static final @NonNull ConcurrentHashMap<@NonNull ToolCallContext, @NonNull Invocation>
            INVOCATIONS = new ConcurrentHashMap<>();
    private static final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Execution>
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

    /**
     * Opens an isolated child for the current authorized parent invocation, clamped to host
     * ceilings. The {@code admitted} supplier must stay true for the child's whole lifetime.
     */
    public @NonNull Child open(
            IsolatedAgent.@NonNull Spec spec,
            IsolatedAgent.@NonNull Factory factory,
            @NonNull BooleanSupplier admitted) {
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
                new Execution(
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
            @NonNull String owner, @NonNull List<@NonNull String> tiers) {
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
    public static final class Execution implements IsolatedAgent.Runtime {
        private final @NonNull ToolCallContext parent;
        private final @NonNull Thread parentThread;
        private final @NonNull BooleanSupplier admitted;
        private final @NonNull String id;
        private final @NonNull String model;
        private final long started = System.nanoTime();
        private final long deadline;
        private final IsolatedAgent.@NonNull Terminal terminal;
        private final IsolatedAgent.@NonNull Limits limits;
        private @NonNull Set<@NonNull String> privateTools = Set.of();
        private final @NonNull AtomicInteger calls = new AtomicInteger();
        private final @NonNull AtomicLong input = new AtomicLong(), output = new AtomicLong();
        private volatile int observationBudget;
        private @NonNull Runnable checkTools = () -> {};
        private volatile boolean closed;
        private volatile boolean completed;
        private HttpDestinationGrant destination;

        private Execution(
                @NonNull ToolCallContext parent,
                @NonNull Thread parentThread,
                @NonNull BooleanSupplier admitted,
                @NonNull String id,
                @NonNull String model,
                IsolatedAgent.@NonNull Limits limits,
                IsolatedAgent.@NonNull Terminal terminal) {
            this.parent = parent;
            this.parentThread = parentThread;
            this.admitted = admitted;
            this.id = id;
            this.model = model;
            this.deadline = started + limits.timeout().toNanos();
            this.terminal = terminal;
            this.limits = limits;
        }

        public @NonNull String id() {
            return id;
        }

        public long deadline() {
            return deadline;
        }

        public @NonNull ToolCallContext parent() {
            return parent;
        }

        /** Binds the one HTTP destination grant this execution may use; binding twice fails. */
        public void bind(@NonNull HttpDestinationGrant grant) {
            authorizeParent();
            if (destination != null) throw new SecurityException("Destination already bound");
            destination = grant;
        }

        /** Verifies the caller is still the original parent invocation and it is not cancelled. */
        public void authorizeParent() {
            var context = ToolCallContextHolder.get();
            if (context == null
                    || !parent.equals(
                            CapabilityAccess.require(context.executionPermit().capability())))
                throw new SecurityException("Parent invocation changed");
            if (parentThread.isInterrupted() || !admitted.getAsBoolean())
                throw new CancellationException("Parent invocation cancelled");
        }

        /** Throws {@link CancellationException} once closed, cancelled, or past the deadline. */
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

        /** Verifies the current tool invocation is the given private tool of this execution. */
        public void authorize(@NonNull String operation) {
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

        /** True while the parent invocation remains admitted and the deadline has not passed. */
        public boolean provenanceLive() {
            return !parentThread.isInterrupted()
                    && admitted.getAsBoolean()
                    && System.nanoTime() < deadline;
        }

        /** Input bytes still available for observations in the next model call. */
        public int observationBudgetBytes() {
            return observationBudget;
        }

        public IsolatedAgent.@NonNull Usage usage() {
            return new IsolatedAgent.Usage(
                    id,
                    model,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    calls.get(),
                    input.get(),
                    output.get());
        }

        /** Settles the declaring invocation with the result through the terminal tool. */
        public void complete(@NonNull String result) {
            authorize(terminal.tool());
            completed = true;
            ToolCallContextHolder.finish(result);
        }
    }

    /** A live isolated child agent; owned by the opening parent invocation and closed with it. */
    public static final class Child implements IsolatedAgent {
        private final @NonNull Execution scope;
        private final IsolatedAgent.@NonNull Tools tools;
        private final @NonNull VetoAgent agent;
        private final @NonNull AgentRunner runner;
        private RequestHandle request;
        private volatile boolean closed;
        private final @NonNull AtomicBoolean closeRequested = new AtomicBoolean();
        private final @NonNull AtomicLong terminationDeadline = new AtomicLong();
        private @NonNull Runnable onClosed = () -> {};

        /** Registers the close callback, run immediately when the child is already closed. */
        public synchronized void onClosed(@NonNull Runnable callback) {
            if (closed) callback.run();
            else onClosed = callback;
        }

        private Child(
                @NonNull Execution scope,
                IsolatedAgent.@NonNull Tools tools,
                @NonNull VetoAgent agent,
                @NonNull AgentRunner runner) {
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

        public @NonNull Execution scope() {
            return scope;
        }

        public @NonNull String id() {
            return agent.id();
        }

        public @NonNull AgentState state() {
            return agent.state();
        }

        public IsolatedAgent.@NonNull Limits limits() {
            return scope.limits;
        }

        public IsolatedAgent.@NonNull Usage usage() {
            return scope.usage();
        }

        public boolean budgetExhausted() {
            return runner.budgetExhausted();
        }

        /** Submits the single request this child accepts; a second submission fails. */
        public synchronized AgentHost.@NonNull Request submit(@NonNull String prompt) {
            scope.authorizeParent();
            scope.check();
            if (request != null)
                throw new IllegalStateException("Isolated child accepts one request");
            var handle = agent.submitRequest(prompt);
            request = handle;
            return new AgentHost.Request() {
                public @NonNull String id() {
                    return handle.requestId();
                }

                public @NonNull CompletableFuture<@NonNull AgentResult> result() {
                    return handle.result().copy();
                }

                public @NonNull CompletableFuture<@NonNull Boolean> settled() {
                    return handle.settled().copy();
                }

                public boolean cancel(@NonNull Duration timeout) throws InterruptedException {
                    return agent.cancelTask(handle, timeout);
                }
            };
        }

        /** True once closed after {@code complete} and the submitted request succeeded. */
        public boolean settledSuccessfully() {
            var active = request;
            return closed
                    && scope.completed
                    && active != null
                    && active.settled().isDone()
                    && active.result().isDone()
                    && active.result().join().success();
        }

        /** Blocks until the child agent terminates or the timeout elapses; true when terminated. */
        public boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
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

        /**
         * Terminates the child and releases its private tools, waiting briefly for exit.
         *
         * @throws IllegalStateException when termination does not finish within the close deadline
         */
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
