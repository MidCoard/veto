package top.focess.veto.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.AgentRuntimeState.BreakerTripException;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.model.tier.ModelTierRegistry;

/** Compiles and measures exchanges against one immutable configuration per model exchange. */
final class ModelSession {
    record Prepared(@NonNull CompiledPrompt prompt, @NonNull Configuration configuration) {}

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.ModelSession");

    record Configuration(
            @NonNull AgentPersona persona,
            @NonNull LlmBinding binding,
            @NonNull ToolResultPresentationMode presentation,
            String owner,
            ModelTierRegistry tiers,
            IsolatedAgent.Terminal terminal,
            @NonNull Workspace workspace) {}

    private final @NonNull String agentId;
    private final @NonNull PromptCompiler compiler;
    private final @NonNull ModelResponseValidation responses;
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull AgentOutput output;
    private final @NonNull AgentPluginHooks hooks;
    private final @NonNull Supplier<LoopBreaker> breaker;
    private final @NonNull Supplier<Configuration> configuration;
    private final @NonNull Runnable check;
    private final @NonNull Runnable reserve;
    private final @NonNull Runnable trip;
    private double correctionFactor = 1.0;
    private volatile PluginContextSnapshot lastPluginContext;

    ModelSession(
            @NonNull String agentId,
            @NonNull PromptCompiler compiler,
            @NonNull ModelResponseValidation responses,
            @NonNull ToolEngine toolEngine,
            @NonNull AgentOutput output,
            @NonNull AgentPluginHooks hooks,
            @NonNull Supplier<LoopBreaker> breaker,
            @NonNull Supplier<Configuration> configuration,
            @NonNull Runnable check,
            @NonNull Runnable reserve,
            @NonNull Runnable trip) {
        this.agentId = agentId;
        this.compiler = compiler;
        this.responses = responses;
        this.toolEngine = toolEngine;
        this.output = output;
        this.hooks = hooks;
        this.breaker = breaker;
        this.configuration = configuration;
        this.check = check;
        this.reserve = reserve;
        this.trip = trip;
    }

    @NonNull PluginContextSnapshot pluginContext() {
        var last = lastPluginContext;
        return last == null
                ? PluginContextSnapshot.from(
                        configuration.get().persona().whitelistedTools(), false)
                : last;
    }

    private @NonNull Set<String> toolNames(@NonNull Configuration current) {
        return current.persona().whitelistedTools().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
    }

    ModelExchange.@NonNull Result callModel(Prepared firstPrompt) {
        Configuration current =
                firstPrompt == null ? configuration.get() : firstPrompt.configuration();
        var terminal = current.terminal();
        return callModel(
                firstPrompt == null ? null : firstPrompt.prompt(),
                null,
                terminal == null
                        ? ResponseContract.ordinary()
                        : ResponseContract.completion(terminal.tool(), false),
                current);
    }

    ModelExchange.@NonNull Result callModel(
            CompiledPrompt firstPrompt,
            PluginWork.ModelInput generation,
            @NonNull ResponseContract contract) {
        return callModel(firstPrompt, generation, contract, configuration.get());
    }

    private ModelExchange.@NonNull Result callModel(
            CompiledPrompt firstPrompt,
            PluginWork.ModelInput generation,
            @NonNull ResponseContract contract,
            @NonNull Configuration current) {
        ModelRequests requests = requests(current);
        CompiledPrompt compiled = firstPrompt;
        if (compiled == null) {
            refreshSystemHistory(current);
            compiled =
                    requests.compilePrompt(output.history(), generation != null, correctionFactor);
        }
        VetoRequest request = requests.buildRequest(compiled).withResponseContract(contract);
        if (generation != null) request = requests.generationRequest(request, generation);
        try {
            return new ModelExchange(agentId, responses)
                    .complete(
                            request,
                            requests,
                            toolNames(current),
                            output::history,
                            modelRuntime(current, requests),
                            correctionFactor,
                            output.requestIdentity(),
                            hooks.responsePolicies());
        } catch (LlmException error) {
            check.run();
            output.appendObservation(
                    "llm_error",
                    error.getMessage() == null
                            ? "LLM call failed without a message"
                            : error.getMessage());

            throw error;
        }
    }

    ModelExchange.@NonNull Runtime modelRuntime(
            @NonNull Configuration current, @NonNull ModelRequests requests) {
        return new ModelExchange.Runtime() {
            @Override
            public @NonNull VetoRequest prepare(@NonNull VetoRequest request) {
                var ledger = breaker.get();
                return terminalOnly(ledger.count(), current.terminal())
                        ? requests.completionRequest(
                                request, current.terminal(), ledger.grantedCalls() - ledger.count())
                        : request;
            }

            @Override
            public ModelExchange.@NonNull Attempt invoke(
                    @NonNull VetoRequest request, double estimateFactor) {
                return performModelCall(request, estimateFactor);
            }

            @Override
            public void rejected(@NonNull ModelSchemaException e) {
                output.appendTurn(
                        new TurnRecord(
                                output.nextTurn(),
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

    ModelExchange.@NonNull Attempt performModelCall(
            @NonNull VetoRequest request, double estimateFactor) {
        long estimatedTokens;
        VetoResponse response;
        if (breaker.get().shouldTrip()) {
            trip.run();
            throw new BreakerTripException();
        }
        check.run();
        reserve.run();
        request = compiler.fitRequest(request, correctionFactor);
        estimatedTokens = compiler.estimateRequest(request, estimateFactor);
        log.debug(
                "Agent {} input: model={}, messages={}, estimatedTokens={},"
                        + " correctionFactor={}",
                agentId,
                request.modelName(),
                request.messages().size(),
                estimatedTokens,
                correctionFactor);
        int requestThroughTurn = output.turnNumber();
        String modelCallId = UUID.randomUUID().toString();
        LlmSystemUsage.begin();
        try {
            check.run();
            lastPluginContext =
                    PluginContextSnapshot.from(
                            toolEngine.getActiveTools(
                                    request.tools().stream()
                                            .map(top.focess.veto.api.llm.ToolDefinition::name)
                                            .collect(Collectors.toSet())),
                            true);
            response = hooks.callModelWithHooks(request);
            check.run();
        } finally {
            List<LlmSystemUsage.Usage> measurements = LlmSystemUsage.drain();
            for (LlmSystemUsage.Usage measured : measurements) {
                UsageMeasurement measurement = UsageMeasurement.measured(request, measured);
                modelCallId = UUID.randomUUID().toString();
                measurement = measurement.forRequest(modelCallId);
                output.recordUsage(requestThroughTurn, measurement);
            }
            if (!measurements.isEmpty()
                    && estimatedTokens > 0
                    && measurements.getLast().promptTokens() > 0) {
                double rawRatio =
                        measurements.getLast().promptTokens() * estimateFactor / estimatedTokens;
                correctionFactor = 0.9 * correctionFactor + 0.1 * rawRatio;
            }
        }

        return new ModelExchange.Attempt(request, response, modelCallId);
    }

    boolean terminalOnly(long completedCalls, IsolatedAgent.Terminal terminal) {
        long limit = breaker.get().grantedCalls();
        return terminal != null && limit > 0 && completedCalls >= limit - terminal.reservedCalls();
    }

    @NonNull ModelRequests requests() {
        return requests(configuration.get());
    }

    private @NonNull ModelRequests requests(@NonNull Configuration current) {
        return new ModelRequests(
                compiler,
                current.workspace(),
                current.persona(),
                current.binding(),
                current.presentation(),
                current.owner(),
                current.tiers(),
                responses);
    }

    @NonNull Prepared preparePrompt(@NonNull List<TurnRecord> history, boolean scoped) {
        Configuration current = configuration.get();
        return new Prepared(
                requests(current).compilePrompt(history, scoped, correctionFactor), current);
    }

    @NonNull String linkCurrentSystemMessage() {
        return linkCurrentSystemMessage(configuration.get());
    }

    private @NonNull String linkCurrentSystemMessage(@NonNull Configuration current) {
        PromptSource.Rendered source =
                compiler.linkSystemSource(
                        current.persona(),
                        current.workspace(),
                        current.binding().systemPromptBase(),
                        current.presentation());
        output.systemSource(source);
        return source.text();
    }

    void refreshSystemHistory() {
        refreshSystemHistory(configuration.get());
    }

    private void refreshSystemHistory(@NonNull Configuration current) {
        List<TurnRecord> additions;
        List<TurnRecord> history = output.history();
        additions =
                HistoryProjection.reinitialize(
                        history,
                        output.turnNumber(),
                        current.persona().role().name(),
                        linkCurrentSystemMessage(current),
                        current.binding().provider().name(),
                        current.binding().model());
        for (TurnRecord record : additions) {

            output.appendTurn(record);
        }
    }

    void rewindAndRestoreHistory() {
        Configuration current = configuration.get();
        List<TurnRecord> additions;
        List<TurnRecord> history = output.history();
        additions =
                HistoryProjection.reinitializeWithRewind(
                        history,
                        output.turnNumber(),
                        current.persona().role().name(),
                        linkCurrentSystemMessage(current),
                        current.binding().provider().name(),
                        current.binding().model());
        for (TurnRecord record : additions) {

            output.appendTurn(record);
        }
    }

    void appendAgentInit(@NonNull String systemPrompt) {
        Configuration snapshot = configuration.get();
        LlmBinding current = snapshot.binding();
        String role = snapshot.persona().role().name().toLowerCase(Locale.ROOT);
        output.appendTurn(
                TurnRecord.agentInit(
                        output.nextTurn(),
                        role,
                        systemPrompt,
                        current.provider().name(),
                        current.model()));
    }
}
