package top.focess.veto.agent.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.WebReadCapability;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.web.*;
import top.focess.veto.api.web.Execution;
import top.focess.veto.api.web.FetchedPage;
import top.focess.veto.api.web.ReaderSession;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierConfigException;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.vault.UserContext;

/** Starts a single-document AgentRunner and collects its validated terminal result. */
@Component
public final class WebFetchExecutor {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.web.WebFetchExecutor");
    private static final int PROVIDER_FRAMING_RESERVE = 2048;
    private static final @NonNull String SYSTEM =
            PromptCompiler.compileText("web-fetch-system-prompt");
    private final @NonNull SessionAgentRegistry sessionAgents;
    private final @NonNull TurnLogService turnLogService;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull ModelTierRegistry models;
    private final @NonNull CapabilityTranslator translator;
    private final @NonNull IngressDefense ingressDefense;
    private final @NonNull ModelTier tier;
    private final int maxRounds;
    private final int timeoutSeconds;
    private final int maxInputTokens;
    private final int maxOutputTokens;

    public WebFetchExecutor(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull UniformLLMCaller caller,
            @NonNull ModelTierRegistry models,
            @NonNull CapabilityTranslator translator,
            @NonNull SessionAgentRegistry sessionAgents,
            @NonNull TurnLogService turnLogService,
            @NonNull IngressDefense ingressDefense,
            @Value("${veto.webfetch.model-tier}") @NonNull ModelTier tier,
            @Value("${veto.webfetch.max-rounds}") int maxRounds,
            @Value("${veto.webfetch.timeout-seconds}") int timeoutSeconds,
            @Value("${veto.webfetch.max-input-tokens}") int maxInputTokens,
            @Value("${veto.webfetch.max-output-tokens}") int maxOutputTokens) {
        if (maxRounds < 3 || timeoutSeconds < 1 || maxInputTokens < 16000 || maxOutputTokens < 256)
            throw new IllegalArgumentException("Invalid webread execution limits.");
        this.sessionAgents = sessionAgents;
        this.turnLogService = turnLogService;
        this.mapper = mapper;
        this.caller = caller;
        this.models = models;
        this.translator = translator;
        this.ingressDefense = ingressDefense;
        this.tier = tier;
        this.maxRounds = maxRounds;
        this.timeoutSeconds = timeoutSeconds;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    public @NonNull String read(
            @NonNull String objective,
            @NonNull WebReadCapability access,
            ReaderSession.@NonNull Factory factory) {
        var parent = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_fetch");
        String owner = parent.owner();
        UUID sessionId = parent.sessionId();
        if (owner == null || sessionId == null || !owner.equals(UserContext.get()))
            return ToolErrors.refused(
                    ToolErrorCode.READER.READER_IDENTITY,
                    "Reader identity: an authenticated session owner is required.");
        var model = resolveModel(owner, tier);
        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(timeoutSeconds).toNanos();
        String id = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong input = new AtomicLong();
        AtomicLong output = new AtomicLong();
        access.bindReader(id);
        try (ReaderSession document =
                factory.open(
                        new ReaderSession.Runtime() {
                            public void authorize(@NonNull String operation) {
                                checkDeadline(deadline);
                                var context =
                                        CapabilityAccess.require(
                                                ToolCapability.NETWORK_EGRESS, operation);
                                if (!id.equals(context.agentId())
                                        || !parent.userId().equals(context.userId())
                                        || !Objects.equals(parent.owner(), context.owner())
                                        || !sessionId.equals(context.sessionId()))
                                    throw new SecurityException(
                                            "This document belongs to another reader invocation");
                            }

                            public @NonNull FetchedPage fetch() {
                                return access.fetch(deadline);
                            }

                            public @NonNull Execution execution() {
                                return new Execution(
                                        id,
                                        model.model(),
                                        Duration.ofNanos(System.nanoTime() - start).toMillis(),
                                        calls.get(),
                                        input.get(),
                                        output.get());
                            }

                            public void completed() {
                                ToolCallContextHolder.registerReaderExecution(UUID.fromString(id));
                            }
                        })) {
            var engine = ToolEngineImpl.isolated(mapper, document.tools());
            var persona =
                    new AgentPersona(
                            id,
                            "web_fetch · 网页阅读",
                            PromptCompiler.compileText("reader-persona-description"),
                            Set.copyOf(engine.getActiveTools(null)),
                            List.of());
            var workspace = Workspace.fromConfig("", "", "REAL");
            var gateway =
                    new Gateway(
                            workspace,
                            new DangerComputation(),
                            SlmScreeningProvider.unavailable(),
                            parent.executionPermit().deployerPolicy(),
                            new ProtectedSet(parent.executionPermit().protectedPaths()),
                            new ReadHistory());
            var options =
                    new LlmOptions(
                            model.temperature(),
                            null,
                            Math.min(maxOutputTokens, model.maxOutputTokens()),
                            Duration.ofSeconds(timeoutSeconds),
                            model.contextWindowTokens());
            int inputBudget = (int) Math.min(maxInputTokens, options.inputBudget());
            var compiler = PromptCompiler.isolated(translator, mapper, SYSTEM, inputBudget);
            // Instrument the shared loop's calls; scheduling, repair, dispatch and history stay in
            // AgentRunner.
            UniformLLMCaller measured =
                    request -> {
                        checkDeadline(deadline);
                        var failure = document.failure();
                        if (failure != null) throw failure;
                        int overhead =
                                bytes(request.systemPrompt())
                                        + bytes(objective)
                                        + bytes(
                                                json(
                                                        request.tools().stream()
                                                                .map(ToolDefinition::wireView)
                                                                .toList()))
                                        + bytes(json(request.responseSchema()))
                                        + PROVIDER_FRAMING_RESERVE;
                        document.setObservationBudget(inputBudget - overhead);
                        calls.incrementAndGet();
                        var remaining =
                                new LlmOptions(
                                        options.temperature(),
                                        options.topP(),
                                        options.maxTokens(),
                                        Duration.ofNanos(Math.max(1, deadline - System.nanoTime())),
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
                                        request.responseSchema(),
                                        request.baseUrl(),
                                        request.nativeToolsEnabled(),
                                        request.responseContract());
                        try {
                            return caller.call(bounded);
                        } finally {
                            for (var usage : LlmSystemUsage.snapshot()) {
                                input.addAndGet(usage.promptTokens());
                                output.addAndGet(usage.completionTokens());
                            }
                        }
                    };
            var runner =
                    new AgentRunner(
                            id,
                            persona,
                            engine,
                            gateway,
                            new HitlRegistry(),
                            ingressDefense,
                            List.of(),
                            compiler,
                            measured,
                            mapper,
                            maxRounds,
                            new LlmBinding(
                                    model.provider(),
                                    model.model(),
                                    model.credentialKey(),
                                    options,
                                    null,
                                    model.baseUrl()),
                            null,
                            parent.userId(),
                            turnLogService,
                            null);
            runner.setOwner(owner);
            runner.setSessionId(sessionId);
            runner.setCompletionTool("finish_read");
            var agent =
                    sessionAgents.startChild(
                            sessionId,
                            parent.agentId(),
                            parent.executionPermit().callId(),
                            persona,
                            runner);
            log.info(
                    "Web reader started: execution={}, parentCall={}, agent={}, model={}",
                    id,
                    parent.executionPermit().callId(),
                    parent.agentId(),
                    model.model());
            try {
                agent.submit(objective);
                var completed =
                        agent.await(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                checkDeadline(deadline);
                var failure = document.failure();
                if (failure != null) throw failure;
                var result = document.result();
                if (!completed.success() || result == null) {
                    if (calls.get() >= maxRounds) {
                        return ToolErrors.failure(
                                ToolErrorCode.READER.READER_BUDGET,
                                "Reader budget: the reader exhausted its execution budget without a"
                                        + " validated result.");
                    }
                    return ToolErrors.failure(
                            ToolErrorCode.READER.READER_MODEL,
                            "Reader model: the reader ended without a validated result; check its"
                                    + " configured model.");
                }
                log.info(
                        "Web reader finished: execution={}, outcome={}, rounds={}, promptTokens={}, completionTokens={}",
                        id,
                        result.outcome(),
                        calls.get(),
                        input.get(),
                        output.get());
                ToolCallContextHolder.registerReaderExecution(UUID.fromString(id));
                return json(result);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return ToolErrors.failure(
                        ToolErrorCode.LIFECYCLE.CANCELLED,
                        "Cancelled: the web reader was cancelled.");
            } catch (TimeoutException error) {
                return ToolErrors.failure(
                        ToolErrorCode.READER.READER_TIMEOUT,
                        "Reader timeout: the web reader exceeded its time budget.");
            } finally {
                // A terminal result/state is set before the child thread actually unwinds.
                // Keep the parent operation occupied until its child can no longer execute.
                boolean interrupted = Thread.interrupted();
                try {
                    // stop also persists the child's lifecycle; an interrupt would close its
                    // database socket before the actual termination wait even begins.
                    sessionAgents.stop(id);
                    boolean exited = false;
                    while (!exited) {
                        try {
                            exited = agent.awaitTermination(Duration.ofMillis(100));
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private @NonNull ModelBinding resolveModel(
            @NonNull String owner, @NonNull ModelTier candidate) {
        try {
            var model = models.resolve(owner, candidate);
            log.info(
                    "Web reader model resolved: requestedTier={}, selectedTier={}, model={}",
                    tier,
                    candidate,
                    model.model());
            return model;
        } catch (ModelTierConfigException error) {
            ModelTier next =
                    switch (candidate) {
                        case LOCAL -> ModelTier.LOW;
                        case LOW -> ModelTier.MID;
                        case MID -> ModelTier.TOP;
                        case TOP -> null;
                    };
            if (next == null) throw error;
            log.info("Web reader tier {} is not configured; trying {}", candidate, next);
            return resolveModel(owner, next);
        }
    }

    static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted())
            ToolErrors.failure(
                    ToolErrorCode.LIFECYCLE.CANCELLED, "Cancelled: the web reader was cancelled.");
        if (System.nanoTime() >= deadline)
            ToolErrors.failure(
                    ToolErrorCode.READER.READER_TIMEOUT,
                    "Reader timeout: the web reader exceeded its time budget.");
    }

    private static int bytes(@NonNull String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private @NonNull String json(Object value) {
        if (value == null) return "null";
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return ToolErrors.failure(
                    ToolErrorCode.READER.READER_OUTPUT,
                    "Reader output: the reader result could not be encoded.");
        }
    }
}
