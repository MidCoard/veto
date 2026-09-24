package top.focess.veto.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.BreakerTripException;
import top.focess.veto.agent.AgentRuntimeState.TaskCancellation;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.response.ResponseRequest;
import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.GenerateAction;
import top.focess.veto.api.agent.workflow.PlanExecution;
import top.focess.veto.api.agent.workflow.PlanStepContext;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.util.Nullness;

/** Builds model exchanges and adapts plugin continuations to authorized host operations. */
final class AgentModelExecution {
    private final @NonNull AgentRuntimeState runtime;

    AgentModelExecution(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    @NonNull VetoResponse takeResponse(
            ToolCallContextHolder.ResponseDirective.@NonNull Answer directive) {
        runtime.pendingResponse = null;
        runtime.lastCitations = directive.citations();
        return directive.response();
    }

    ToolCallContextHolder.@NonNull ResponseDirective validateSubmission(
            @NonNull ResponseRequest submission) throws Exception {
        return runtime.responses.validateSubmission(
                submission,
                runtime.submissionRequest,
                runtime.submissionGeneration,
                runtime.completionTool,
                runtime.whitelistedTools,
                runtime.output().history(),
                runtime.gateway);
    }

    ResponseSubmission.Kind submissionKind(@NonNull String name) {
        return runtime.responses.submissionKind(name);
    }

    void runAutonomous() {
        while (runtime.state == AgentState.RUNNING) {
            runtime.lifecycle().checkTaskCancellation();
            // Mid-episode task lifecycle: a background task that ended (or that the user
            // stopped) during THIS episode is reported at the next iteration, not only at the
            // start of the next episode. Cheap no-op when the queue is empty.
            // processUserPrompt already drained notices before preparing the first immutable
            // request. Do not mutate history between that compilation and its dispatch.
            if (runtime.preparedFirstPrompt == null) {
                runtime.monitor().injectPendingTaskExitNotices();
                runtime.monitor().injectMonitorEvents();
            }
            if (runtime.pendingResponse == null && runtime.breaker.shouldTrip()) {
                runtime.output().tripBreaker();
                throw new BreakerTripException();
            }
            var accepted = runtime.pendingResponse;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Plan plan) {
                runtime.pendingResponse = null;
                runtime.lifecycle().checkTaskCancellation();
                PlanExecution acceptedProgram = plan.execution();
                acceptedProgram.configure(runtime.maxPlanSteps);
                acceptedProgram.install(plan.program(), runtime.lastModelCallId);
                runtime.program = acceptedProgram;
                AgentPersona programPersona = runtime.persona;
                runSubmittedPlan();
                if (runtime.persona != programPersona) continue;
                return;
            }
            VetoResponse response =
                    accepted instanceof ToolCallContextHolder.ResponseDirective.Answer answer
                            ? takeResponse(answer)
                            : callModel();
            runtime.lifecycle().checkTaskCancellation();

            runtime.output().appendThought(response);
            String message = response.message();
            if (message != null && !message.isBlank()) {
                var calls = response.calls();
                runtime.output()
                        .emitMessage(
                                message,
                                runtime.lastCitations,
                                runtime.lastModelCallId,
                                false,
                                calls != null
                                        && calls.stream()
                                                .anyMatch(call -> call.nativeState() != null));
            }
            List<ToolCall> responseCalls = response.calls();
            if (responseCalls != null && !responseCalls.isEmpty()) {
                runtime.tools().executeToolCalls(responseCalls, response.thought());
                if (runtime.completionToolFinished) return;
            } else {
                // No tool calls: the agent has emitted its answer with nothing further to act
                // on. Termination routes on call presence - calls absent means stop. The agent
                // reasons within its model invocation. Stop the episode here; the emitted
                // message is the final answer.
                return;
            }
        }
    }

    void runSubmittedPlan() {
        PlanExecution active = runtime.program;
        if (active != null) active.run(planRuntime(active));
    }

    PlanExecution.@NonNull Runtime planRuntime(@NonNull PlanExecution active) {
        return new PlanExecution.Runtime() {
            @Override
            public void beforeStep() {
                runtime.lifecycle().checkTaskCancellation();
                runtime.monitor().injectPendingTaskExitNotices();
                runtime.monitor().injectMonitorEvents();
            }

            @Override
            public boolean running() {
                return runtime.state == AgentState.RUNNING && runtime.program == active;
            }

            @Override
            public @NonNull ToolResult tool(
                    @NonNull ToolCall call, @NonNull PlanStepContext context) {
                runtime.currentToolModelCallId = context.programModelCallId();
                runtime.currentPlanStep = context;
                try {
                    return runtime.tools().executeOneCall(call);
                } finally {
                    runtime.currentToolModelCallId = null;
                    runtime.currentPlanStep = null;
                }
            }

            @Override
            public PlanExecution.@NonNull Generated generate(
                    @NonNull GenerateAction action, @NonNull ResponseContract contract) {
                VetoResponse response = callGenerate(action, contract);
                return new PlanExecution.Generated(
                        response, runtime.lastCitations, runtime.lastModelCallId);
            }

            @Override
            public void message(
                    @NonNull String text,
                    PlanExecution.Source citations,
                    String callId,
                    boolean forwarded) {
                runtime.output()
                        .emitMessage(
                                text,
                                citations instanceof MessageCitations.Bound bound ? bound : null,
                                callId,
                                forwarded);
            }

            @Override
            public @NonNull String prompt(
                    @NonNull String source, @NonNull Map<String, Object> data) {
                return PromptCompiler.compileText(source, data);
            }

            @Override
            public void escaped(@NonNull String reason) {
                runtime.output().appendObservation("plan_escape", reason);
            }
        };
    }

    @NonNull VetoResponse callGenerate(
            @NonNull GenerateAction gen, @NonNull ResponseContract contract) {
        if (runtime.breaker.shouldTrip()) {
            runtime.output().tripBreaker();
            throw new BreakerTripException();
        }
        VetoResponse response;
        while (true) {
            response = callModel(gen, contract);
            if (!Boolean.FALSE.equals(gen.thought())) runtime.output().appendThought(response);
            var calls = response.calls();
            if (calls == null || calls.isEmpty()) break;
            runtime.tools().executeToolCalls(calls, response.thought());
            var accepted = runtime.pendingResponse;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Answer answer) {
                response = takeResponse(answer);
                break;
            }
            runtime.lifecycle().checkTaskCancellation();
        }
        return response;
    }

    @NonNull VetoResponse callModel() {
        String completion = runtime.completionTool;
        return runtime.models()
                .callModel(
                        null,
                        completion == null
                                ? ResponseContract.ordinary()
                                : ResponseContract.completion(completion, false));
    }

    @NonNull VetoResponse callModel(GenerateAction generation, @NonNull ResponseContract contract) {
        runtime.lastCitations = null;
        CompiledPrompt compiled = runtime.preparedFirstPrompt;
        runtime.preparedFirstPrompt = null;
        if (compiled == null) {
            refreshSystemHistory();
            compiled = compilePrompt(runtime.output().history(), generation != null);
        }
        VetoRequest request = requests().buildRequest(compiled).withResponseContract(contract);
        if (generation != null)
            request =
                    runtime.models()
                            .requests()
                            .generationRequest(
                                    request,
                                    generation,
                                    Nullness.requireNonNull(runtime.program, "No active plan")
                                            .scope());
        try {
            var result =
                    new ModelExchange(runtime.agentId, runtime.responses)
                            .complete(
                                    request,
                                    requests(),
                                    runtime.whitelistedTools,
                                    runtime.output()::history,
                                    modelRuntime(),
                                    runtime.correctionFactor);
            runtime.lastCitations = result.citations();
            runtime.lastModelCallId = result.modelCallId();
            if (result.accepted()) {
                runtime.submissionRequest = result.request();
                runtime.submissionGeneration = generation != null;
            }
            return result.response();
        } catch (LlmException e) {
            // LLM failure → record error, break the loop ( table: LLM Error → IDLE).
            TaskCancellation cancellation = runtime.activeCancellation;
            if (cancellation == null || !cancellation.cancelled) {
                runtime.output()
                        .appendObservation(
                                "llm_error",
                                e.getMessage() == null
                                        ? "LLM call failed without a message"
                                        : e.getMessage());
            }
            runtime.lifecycle().transitionTo(AgentState.IDLE);
            throw e;
        }
    }

    ModelExchange.@NonNull Runtime modelRuntime() {
        return new ModelExchange.Runtime() {
            @Override
            public @NonNull VetoRequest prepare(@NonNull VetoRequest request) {
                return completionOnly(runtime.breaker.count())
                        ? runtime.models()
                                .requests()
                                .completionRequest(
                                        request,
                                        runtime.completionTool,
                                        runtime.breaker.maxCallsPerEpisode()
                                                - runtime.breaker.count())
                        : request;
            }

            @Override
            public ModelExchange.@NonNull Attempt invoke(
                    @NonNull VetoRequest request, double estimateFactor) {
                return performModelCall(request, estimateFactor);
            }

            @Override
            public void rejected(@NonNull ModelSchemaException e) {
                runtime.output()
                        .appendTurn(
                                new TurnRecord(
                                        ++runtime.turnNumber,
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
        if (runtime.breaker.shouldTrip()) {
            runtime.output().tripBreaker();
            throw new BreakerTripException();
        }
        runtime.lifecycle().checkExecutionBoundary();
        runtime.monitor().reserveRequestCall();
        request = runtime.promptCompiler.fitRequest(request, runtime.correctionFactor);
        estimatedTokens = runtime.promptCompiler.estimateRequest(request, estimateFactor);
        AgentRuntimeState.log.debug(
                "Agent {} input: model={}, messages={}, estimatedTokens={},"
                        + " correctionFactor={}",
                runtime.agentId,
                request.modelName(),
                request.messages().size(),
                estimatedTokens,
                runtime.correctionFactor);
        int requestThroughTurn = runtime.turnNumber;
        runtime.lastModelCallId = UUID.randomUUID().toString();
        LlmSystemUsage.begin();
        try {
            runtime.lifecycle().checkTaskCancellation();
            runtime.lastPluginContext =
                    PluginContextSnapshot.from(
                            runtime.toolEngine.getActiveTools(
                                    request.tools().stream()
                                            .map(top.focess.veto.api.llm.ToolDefinition::name)
                                            .collect(Collectors.toSet())),
                            true);
            response = runtime.hooks().callModelWithHooks(request);
            runtime.lifecycle().checkTaskCancellation();
        } finally {
            List<LlmSystemUsage.Usage> measurements = LlmSystemUsage.drain();
            for (LlmSystemUsage.Usage measured : measurements) {
                UsageMeasurement measurement = UsageMeasurement.measured(request, measured);
                runtime.lastModelCallId = UUID.randomUUID().toString();
                measurement = measurement.forRequest(runtime.lastModelCallId);
                runtime.output().recordUsage(requestThroughTurn, measurement);
            }
            if (!measurements.isEmpty()
                    && estimatedTokens > 0
                    && measurements.getLast().promptTokens() > 0) {
                double rawRatio =
                        measurements.getLast().promptTokens() * estimateFactor / estimatedTokens;
                runtime.correctionFactor = 0.9 * runtime.correctionFactor + 0.1 * rawRatio;
            }
        }

        return new ModelExchange.Attempt(request, response, runtime.lastModelCallId);
    }

    boolean completionOnly(long completedCalls) {
        long limit = runtime.breaker.maxCallsPerEpisode();
        return runtime.completionTool != null
                && limit > 0
                && completedCalls >= limit - (limit >= 4 ? 2 : 1);
    }

    @NonNull ModelRequests requests() {
        return new ModelRequests(
                runtime.promptCompiler,
                runtime.gateway.workspace(),
                runtime.persona,
                runtime.binding,
                runtime.toolResultPresentation,
                runtime.owner,
                runtime.planTierRegistry,
                runtime.responses);
    }

    @NonNull CompiledPrompt compilePrompt(@NonNull List<TurnRecord> history, boolean scoped) {
        return runtime.models()
                .requests()
                .compilePrompt(history, scoped, runtime.correctionFactor, runtime.recoveryContext);
    }

    @NonNull String linkCurrentSystemMessage() {
        PromptSource.Rendered source =
                runtime.promptCompiler.linkSystemSource(
                        runtime.persona,
                        runtime.gateway.workspace(),
                        runtime.binding.systemPromptBase(),
                        runtime.toolResultPresentation);
        runtime.currentSystemSource = source;
        return source.text();
    }

    void refreshSystemHistory() {
        List<TurnRecord> additions;
        List<TurnRecord> history = runtime.output().history();
        additions =
                HistoryProjection.reinitialize(
                        history,
                        runtime.turnNumber,
                        runtime.persona.role().name(),
                        linkCurrentSystemMessage(),
                        runtime.binding.provider().name(),
                        runtime.binding.model());
        for (TurnRecord record : additions) {
            runtime.turnNumber = record.turnNumber();
            runtime.output().appendTurn(record);
        }
    }

    void rewindAndRestoreHistory() {
        List<TurnRecord> additions;
        List<TurnRecord> history = runtime.output().history();
        additions =
                HistoryProjection.reinitializeWithRewind(
                        history,
                        runtime.turnNumber,
                        runtime.persona.role().name(),
                        linkCurrentSystemMessage(),
                        runtime.binding.provider().name(),
                        runtime.binding.model());
        for (TurnRecord record : additions) {
            runtime.turnNumber = record.turnNumber();
            runtime.output().appendTurn(record);
        }
    }

    void appendAgentInit(@NonNull String systemPrompt) {
        LlmBinding current = runtime.binding;
        String role = runtime.persona.role().name().toLowerCase(Locale.ROOT);
        runtime.output()
                .appendTurn(
                        TurnRecord.agentInit(
                                ++runtime.turnNumber,
                                role,
                                systemPrompt,
                                current.provider().name(),
                                current.model()));
    }
}
