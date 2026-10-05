package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.AgentRunner.BreakerTripException;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.i18n.Msg;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

/** Compiles and measures exchanges against one immutable configuration per model exchange. */
final class ModelSession {
    record Prepared(@NonNull CompiledPrompt prompt, @NonNull Configuration configuration) {}

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.ModelSession");

    record Configuration(
            @NonNull AgentPersona persona,
            @NonNull LlmBinding binding,
            AgentProfile.Prompt prompt,
            @NonNull ToolResultPresentationMode presentation,
            @NonNull UUID userId,
            ModelTierRegistry tiers,
            IsolatedAgent.Terminal terminal,
            @NonNull Workspace workspace) {}

    private final @NonNull String agentId;
    private final @NonNull PromptCompiler compiler;
    private final @NonNull ModelResponseValidation responses;
    private final @NonNull ToolEngine toolEngine;
    final @NonNull AgentOutput output;
    final @NonNull AgentRunner runner;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull ModelFlowStack flows = new ModelFlowStack();
    private final @NonNull AgentPluginHooks hooks;
    private double correctionFactor = 1.0;
    private volatile PluginContextSnapshot lastPluginContext;

    ModelSession(
            @NonNull String agentId,
            @NonNull PromptCompiler compiler,
            @NonNull ModelResponseValidation responses,
            @NonNull ToolEngine toolEngine,
            @NonNull AgentOutput output,
            @NonNull AgentPluginHooks hooks,
            @NonNull AgentRunner runner,
            @NonNull ObjectMapper mapper) {
        this.agentId = agentId;
        this.compiler = compiler;
        this.responses = responses;
        this.toolEngine = toolEngine;
        this.output = output;
        this.hooks = hooks;
        this.runner = runner;
        this.mapper = mapper;
    }

    @NonNull PluginContextSnapshot pluginContext() {
        var last = lastPluginContext;
        return last == null
                ? PluginContextSnapshot.from(
                        runner.modelConfiguration().persona().whitelistedTools(), false)
                : last;
    }

    private @NonNull Set<String> toolNames(@NonNull Configuration current) {
        return current.persona().whitelistedTools().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
    }

    ModelExchange.@NonNull Result callModel(Prepared firstPrompt) {
        Configuration current =
                firstPrompt == null ? runner.modelConfiguration() : firstPrompt.configuration();
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
            ModelFlow.ModelInput generation,
            @NonNull ResponseContract contract) {
        return callModel(firstPrompt, generation, contract, runner.modelConfiguration());
    }

    private ModelExchange.@NonNull Result callModel(
            CompiledPrompt firstPrompt,
            ModelFlow.ModelInput generation,
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
            runner.checkExecutionBoundary();
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
                var ledger = runner.currentRequest().episode.breaker();
                return terminalOnly(ledger, current.terminal())
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
        checkBudget();
        runner.checkExecutionBoundary();
        runner.continuations.reserveRequestCall(runner.currentRequest());
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
        return invoke(request, estimatedTokens, estimateFactor, false);
    }

    private ModelExchange.@NonNull Attempt invoke(
            @NonNull VetoRequest request,
            long estimatedTokens,
            double estimateFactor,
            boolean compaction) {
        Runnable boundary =
                compaction ? runner::checkTaskCancellation : runner::checkExecutionBoundary;
        int throughTurn = output.turnNumber();
        String callId = UUID.randomUUID().toString();
        VetoResponse response;
        LlmSystemUsage.begin();
        try {
            boundary.run();
            if (!compaction) {
                lastPluginContext =
                        PluginContextSnapshot.from(
                                toolEngine.getActiveTools(
                                        request.tools().stream()
                                                .map(top.focess.veto.api.llm.ToolDefinition::name)
                                                .collect(Collectors.toSet())),
                                true);
            }
            response = hooks.callModelWithHooks(request);
            boundary.run();
        } finally {
            var measurements = LlmSystemUsage.drain();
            for (var measured : measurements) {
                var usage = UsageMeasurement.measured(request, measured);
                if (compaction) output.recordUsage(output.turnNumber(), usage.forCompaction());
                else {
                    callId = UUID.randomUUID().toString();
                    output.recordUsage(throughTurn, usage.forRequest(callId));
                }
            }
            if (!compaction
                    && !measurements.isEmpty()
                    && estimatedTokens > 0
                    && measurements.getLast().promptTokens() > 0) {
                double ratio =
                        measurements.getLast().promptTokens() * estimateFactor / estimatedTokens;
                correctionFactor = 0.9 * correctionFactor + 0.1 * ratio;
            }
        }
        return new ModelExchange.Attempt(request, response, callId);
    }

    boolean terminalOnly(@NonNull LoopBreaker ledger, IsolatedAgent.Terminal terminal) {
        long limit = ledger.grantedCalls();
        return terminal != null && limit > 0 && ledger.count() >= limit - terminal.reservedCalls();
    }

    @NonNull ModelRequests requests() {
        return requests(runner.modelConfiguration());
    }

    private @NonNull ModelRequests requests(@NonNull Configuration current) {
        return new ModelRequests(
                compiler,
                current.workspace(),
                current.persona(),
                current.binding(),
                current.prompt(),
                current.presentation(),
                current.userId(),
                current.tiers(),
                responses);
    }

    @NonNull Prepared preparePrompt(@NonNull List<TurnRecord> history, boolean scoped) {
        Configuration current = runner.modelConfiguration();
        return new Prepared(
                requests(current).compilePrompt(history, scoped, correctionFactor), current);
    }

    @NonNull String linkCurrentSystemMessage() {
        return linkCurrentSystemMessage(runner.modelConfiguration());
    }

    private @NonNull String linkCurrentSystemMessage(@NonNull Configuration current) {
        PromptSource.Rendered source =
                compiler.linkSystemProfile(
                        current.persona(),
                        current.workspace(),
                        current.prompt(),
                        current.presentation());
        output.systemSource(source);
        return source.text();
    }

    void refreshSystemHistory() {
        refreshSystemHistory(runner.modelConfiguration());
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
        Configuration current = runner.modelConfiguration();
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
        Configuration snapshot = runner.modelConfiguration();
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

    void run(Prepared firstPrompt, @NonNull AgentToolExecution tools) {
        drive(firstPrompt, null, null, tools);
    }

    ModelExchange.@NonNull Result generate(
            ModelFlow.@NonNull ModelInput input,
            @NonNull ResponseContract contract,
            @NonNull AgentToolExecution tools) {
        checkBudget();
        return Nullness.requireNonNull(drive(null, input, contract, tools));
    }

    /**
     * One model/tool exchange loop; scoped generation suppresses publication and flow selection.
     */
    private ModelExchange.Result drive(
            Prepared firstPrompt,
            ModelFlow.ModelInput generation,
            ResponseContract contract,
            @NonNull AgentToolExecution tools) {
        ToolCallContextHolder.ResponseDirective pending = null;
        ModelExchange.Result exchange = null;
        String callId = null;
        while (generation != null || runner.control().state() == AgentState.RUNNING) {
            if (generation == null) {
                runner.checkTaskCancellation();
                // A compiled first prompt already includes observations; keep it immutable until
                // dispatch.
                if (firstPrompt == null) runner.injectObservations();
                if (pending == null) checkBudget();
                if (pending instanceof ToolCallContextHolder.ResponseDirective.Push pushed) {
                    pending = null;
                    runner.checkTaskCancellation();
                    flows.push(pushed.flow());
                }
                if (!flows.defaultSelected()) {
                    long revision = runner.configurationRevision();
                    boolean finished = flows.run(this, tools, callId);
                    if (runner.configurationRevision() != revision) continue;
                    if (finished) return null;
                    continue;
                }
            }
            // Finish is consumed at the following boundary, after observation/flow precedence.
            if (pending instanceof ToolCallContextHolder.ResponseDirective.Finish answer) {
                if (generation != null) {
                    return new ModelExchange.Result(
                            Nullness.requireNonNull(exchange).request(),
                            RequestEvidence.response(
                                    answer.message(),
                                    answer.citations(),
                                    output.requestIdentity(),
                                    callId),
                            answer.citations(),
                            callId,
                            true);
                }
                if (answer.publish()) {
                    runner.checkTaskCancellation();
                    output.emitMessage(
                            answer.message(),
                            RequestEvidence.forRequest(
                                    answer.citations(),
                                    output.requestIdentity(),
                                    callId,
                                    answer.message()),
                            callId,
                            false);
                } else runner.currentRequest().message = answer.message();
                return null;
            }
            exchange =
                    generation == null
                            ? callModel(firstPrompt)
                            : callModel(null, generation, Nullness.requireNonNull(contract));
            firstPrompt = null;
            callId = exchange.modelCallId();
            if (generation == null) runner.checkTaskCancellation();
            var response = exchange.response();
            if (generation == null || !Boolean.FALSE.equals(generation.thought()))
                output.appendThought(response, callId);
            var calls = response.calls();
            String text = response.message();
            if (generation == null && text != null && !text.isBlank()) {
                output.emitMessage(
                        text,
                        RequestEvidence.forRequest(
                                exchange.citations(), output.requestIdentity(), callId, text),
                        callId,
                        false,
                        calls != null
                                && calls.stream().anyMatch(call -> call.nativeState() != null));
            }
            if (calls == null || calls.isEmpty()) return exchange;
            pending =
                    tools.executeToolCalls(
                            calls,
                            response.thought(),
                            new ToolBatch(exchange, generation != null, null),
                            runner.toolInvocation());
            if (generation != null
                    && !(pending instanceof ToolCallContextHolder.ResponseDirective.Finish))
                runner.checkTaskCancellation();
        }
        return null;
    }

    private void checkBudget() {
        if (runner.currentRequest().episode.breaker().shouldTrip()) {
            runner.tripBreaker();
            throw new BreakerTripException();
        }
    }

    @NonNull Prepared userPrompt(@NonNull String prompt) {
        var request = runner.currentRequest();
        prompt = hooks.captureUserPrompt(hooks.beforeInput(hooks.captureUserPrompt(prompt)));
        if (runner.control().waiting(Wait.INTERRUPTED)) {
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
        request.declinedCallSignatures.clear();
        String task = request.episode.task();
        String resumed =
                runner.control().waiting(Wait.BREAKER)
                                && "continue".equalsIgnoreCase(prompt.strip())
                        ? (task.isBlank() ? output.latestUserTask() : task)
                        : null;
        request.episode.task(resumed == null ? prompt : resumed);
        request.episode.observationId(null);
        runner.clearWait(Wait.BREAKER);
        runner.clearWait(Wait.INTERRUPTED);
        runner.injectObservations();
        refreshSystemHistory();
        TurnRecord turn =
                resumed == null
                        ? TurnRecord.userPrompt(output.nextTurn(), prompt)
                        : TurnRecord.breakerContinuation(output.nextTurn(), prompt, resumed);
        Map<@NonNull String, @Nullable Object> payload = new LinkedHashMap<>(turn.payload());
        payload.put("requestId", request.episode.id());
        turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        var prospective = new ArrayList<>(output.history());
        prospective.add(turn);
        // No history mutation may intervene between compiling this first request and dispatch.
        var prepared = preparePrompt(prospective, false);
        output.appendTurn(turn);
        if (resumed != null) request.episode.breaker().grantContinuation();
        return prepared;
    }

    void compact() {
        var history = output.history();
        int anchor = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).type() == TurnType.AGENT_INIT) {
                anchor = i;
                break;
            }
        }
        if (anchor >= history.size() - 1) {
            output.emitMessage(Msg.get(runner.locale(), "error.agent.compactNothing"));
            return;
        }
        var turns = history.subList(anchor + 1, history.size());
        String summary = summarize(turns);
        if ("{}".equals(summary)) {
            output.appendObservation(
                    "compaction_failed",
                    "No valid summary was produced; the context was retained.");
            return;
        }
        restart(summary);
        output.compacted(summary, turns.size());
    }

    @NonNull String summarize(@NonNull List<TurnRecord> turns) {
        return new HistoryCompactor(
                        mapper,
                        (system, user) -> requests().compactionRequest(system, user),
                        request -> invoke(request, 0, 1, true).response())
                .summarize(turns);
    }

    @NonNull String summarizeTransition() {
        try {
            return summarize(output.history());
        } catch (RuntimeException error) {
            log.warn(
                    "Agent {} configuration compaction failed; preserving history", agentId, error);
            return "{}";
        }
    }

    void restart(@NonNull String summary) {
        if (summary.isBlank() || "{}".equals(summary)) {
            rewindAndRestoreHistory();
        } else {
            output.appendTurn(TurnRecord.rewind(output.nextTurn(), 0));
            appendAgentInit(linkCurrentSystemMessage());
            output.appendTurn(TurnRecord.compactionSummary(output.nextTurn(), summary));
        }
    }
}
