package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentLifecycle.VetoRefusedException;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.ToolExecutionBoundary.ScreenedInvocation;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.RefusalObservation;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.bus.DeltaFrame;

/**
 * Screens tool batches, obtains approvals and executes under host-issued permits.
 *
 * <p>Execution belongs to the agent loop. Each entry point receives the request and configuration
 * captured for that invocation; a successful effect that changes the lifecycle configuration
 * abandons the remaining calls from that batch. Approval resolution uses the lifecycle monitor to
 * preserve cancellation ordering. Tool implementations and plugin callbacks execute outside that
 * monitor and retain responsibility for their own shared state.
 */
final class AgentToolExecution {
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull ToolExecutionBoundary toolBoundary;
    private final @NonNull ModelResponseValidation responses;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull List<LoopInterceptor> interceptors;
    private final @NonNull AgentOutput output;
    private final @NonNull AgentPluginHooks hooks;
    private final @NonNull AgentLifecycle lifecycle;
    private final @NonNull String agentId;
    private final @NonNull UUID userId;
    private final String owner;
    private final @NonNull UUID sessionId;

    record Invocation(
            @NonNull RequestHandle request,
            @NonNull ToolResultPresentationMode presentation,
            @NonNull Set<String> whitelistedTools,
            @NonNull AgentExecutionPolicy executionPolicy,
            long configurationRevision) {}

    AgentToolExecution(
            @NonNull ToolEngine toolEngine,
            @NonNull ToolExecutionBoundary toolBoundary,
            @NonNull ModelResponseValidation responses,
            @NonNull ObjectMapper objectMapper,
            @NonNull List<LoopInterceptor> interceptors,
            @NonNull AgentOutput output,
            @NonNull AgentPluginHooks hooks,
            @NonNull AgentLifecycle lifecycle,
            @NonNull String agentId,
            @NonNull UUID userId,
            String owner,
            @NonNull UUID sessionId) {
        this.toolEngine = toolEngine;
        this.toolBoundary = toolBoundary;
        this.responses = responses;
        this.objectMapper = objectMapper;
        this.interceptors = List.copyOf(interceptors);
        this.output = output;
        this.hooks = hooks;
        this.lifecycle = lifecycle;
        this.agentId = agentId;
        this.userId = userId;
        this.owner = owner;
        this.sessionId = sessionId;
    }

    ToolCallContextHolder.ResponseDirective executeToolCalls(
            @NonNull List<ToolCall> calls,
            String thought,
            @NonNull ToolBatch batch,
            @NonNull Invocation invocation) {
        lifecycle.transitionTo(AgentState.WAITING);
        try {

            if (calls.size() > 1
                    && calls.stream()
                            .anyMatch(c -> responses.submissionKind(c.toolName()) != null)) {
                for (var call : calls) {
                    output.appendToolCall(call, batch.modelCallId());
                    output.appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            "A response submission must be the only tool call. No calls in this"
                                    + " batch were executed; resubmit separately.",
                            false);
                }
                return batch.control;
            }
            List<ToolCall> callsNeedingDecision = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                if (invocation.request().declinedCallSignatures.contains(toolCallSignature(call))) {
                    output.appendToolCall(call, batch.modelCallId());
                    output.appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            AgentToolExecution.refusedObservation(
                                    PromptCompiler.compileText("runtime-refused-duplicate")),
                            false);
                } else {
                    callsNeedingDecision.add(call);
                }
            }
            if (callsNeedingDecision.isEmpty()) {
                return batch.control;
            }
            calls = callsNeedingDecision;

            // 1. Check phase (screen all calls first)
            Map<String, ScreenedInvocation> screenedInvocations = new LinkedHashMap<>();
            Set<String> cancelledCalls = new HashSet<>();
            boolean hasVeto = false;
            boolean hasRefused = false;
            for (ToolCall call : calls) {
                ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
                if (def != null) {
                    var event = hooks.beforeTool(call);
                    if (event != null && event.isCancelled()) {
                        cancelledCalls.add(call.callId());
                        continue;
                    }
                    var hookDecision = event == null ? BeforeToolEvent.Decision.CONTINUE : event.decision();
                    ScreenedInvocation screened =
                            toolBoundary.assess(
                                    call,
                                    def,
                                    invocation.request().episode.task(),
                                    thought,
                                    batch.step,
                                    invocation.request().episode.id(),
                                    hookDecision);
                    screenedInvocations.put(call.callId(), screened);
                    ApprovalDecision decision = screened.decision();
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
                    var screened = screenedInvocations.get(callId);
                    if (screened == null) continue;
                    ApprovalDecision decision = screened.decision();
                    if (invocation
                            .request()
                            .declinedCallSignatures
                            .contains(toolCallSignature(call))) {
                        skippedCalls.add(call);
                    } else if (decision instanceof ApprovalDecision.Refused r) {
                        output.emitMessage(r.reason());
                        lifecycle.transitionTo(AgentState.INTERCEPTED);
                        List<VetoOption> offered = List.of(VetoOption.EXEC_DECLINE);
                        toolBoundary.register(screened, offered, Danger.CRITICAL, null);
                        output.emitVetoRequired(
                                call,
                                new ApprovalDecision.Prompt(
                                        VetoScenario.GENERIC, offered, Danger.CRITICAL, null),
                                offered);
                        awaitResolution(callId, invocation);
                        refusalDetail =
                                "refused by the security policy (CRITICAL - no approval path)";
                        batchApproved = false;
                        break;
                    } else if (decision instanceof ApprovalDecision.Prompt p) {
                        lifecycle.transitionTo(AgentState.INTERCEPTED);
                        // Register the await target BEFORE advertising the prompt: the veto
                        // listener sends the Prompt synchronously, and the user's reply could
                        // otherwise race register and resolve against a not-yet-registered future.
                        List<VetoOption> offered = p.options();
                        toolBoundary.register(screened, offered, p.danger(), p.relevance());
                        output.emitVetoRequired(call, p, offered);
                        InterceptResolution resolution = awaitResolution(callId, invocation);

                        if (resolution.option() == VetoOption.DECLINE_AND_CONTINUE) {
                            skippedCalls.add(call);
                            invocation
                                    .request()
                                    .declinedCallSignatures
                                    .add(toolCallSignature(call));
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
                        if (cancelledCalls.contains(call.callId())) {
                            cancelledCall(call, batch);
                            continue;
                        }
                        output.appendToolCall(call, batch.modelCallId());
                        output.appendToolResponse(
                                call.toolName(),
                                call.callId(),
                                AgentToolExecution.refusedObservation(refusalDetail),
                                false);
                    }
                    lifecycle.transitionTo(AgentState.IDLE);
                    throw new VetoRefusedException(approvalRequested);
                }
            }

            // 3. Execute phase (all confirmed / skipped)
            if (lifecycle.control().state() == AgentState.INTERCEPTED)
                lifecycle.transitionTo(AgentState.WAITING);
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (cancelledCalls.contains(call.callId())) {
                    cancelledCall(call, batch);
                } else if (skippedCalls.contains(call)) {
                    output.appendToolCall(call, batch.modelCallId());
                    output.appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            AgentToolExecution.refusedObservation(
                                            "declined by the client (DECLINE_AND_CONTINUE)")
                                    + " Continue without this call: do not retry it"
                                    + " unchanged - pick a different approach, or explain"
                                    + " the blockage and stop.",
                            false);
                } else {
                    long configuration = invocation.configurationRevision();
                    executeOneConfirmedCall(
                            call, screenedInvocations.get(call.callId()), batch, invocation);
                    if (batch.control != null) return batch.control;
                    if (lifecycle.configurationRevision() != configuration) {
                        // Remaining calls were authored for the previous configuration.
                        return null;
                    }
                }
            }

        } finally {
            if (lifecycle.control().state() == AgentState.WAITING
                    || lifecycle.control().state() == AgentState.INTERCEPTED) {
                lifecycle.transitionTo(AgentState.RUNNING);
            }
        }
        return batch.control;
    }

    private @NonNull ToolResult executeOneConfirmedCall(
            @NonNull ToolCall call,
            ScreenedInvocation screened,
            @NonNull ToolBatch batch,
            @NonNull Invocation invocation) {
        ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call, batch);
        }
        if (screened == null) throw new IllegalStateException("Missing screened invocation");
        return executeResolvedCall(screened, batch, invocation);
    }

    private @NonNull ToolResult executeResolvedCall(
            @NonNull ScreenedInvocation screened,
            @NonNull ToolBatch batch,
            @NonNull Invocation invocation) {
        ToolCall call = screened.call();
        ToolDefinition def = screened.definition();
        lifecycle.checkExecutionBoundary();
        output.appendToolCall(call, batch.modelCallId());

        ToolExecutionPermit executionPermit;
        var authorized = toolBoundary.authorize(screened);
        try {
            executionPermit = toolBoundary.revalidate(authorized);
        } catch (SecurityException e) {
            String observation =
                    "Filesystem target changed after screening; submit a fresh tool call";
            output.appendToolResponse(call.toolName(), call.callId(), observation, false);
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
                output.appendObservation(call.toolName(), "Blocked by plugin.");
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked by plugin",
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
        }

        // (d) execute with tool call context (agentId + userId + sessionId) threaded through.
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agentId,
                        userId,
                        owner,
                        sessionId,
                        invocation.presentation(),
                        executionPermit.withCaller(agentId, userId, owner, sessionId),
                        invocation.request().episode.id()));
        try {
            if (responses.submissionKind(call.toolName()) != null) {
                var exchange = batch.exchange;
                var request = exchange != null && exchange.accepted() ? exchange.request() : null;
                if (request != null
                        && request.nativeToolsEnabled()
                        && request.tools().stream()
                                .anyMatch(t -> t.name().equals(call.toolName()))) {
                    var context = ToolCallContextHolder.get();
                    if (context == null)
                        throw new IllegalStateException("Missing admitted invocation");
                    if (invocation.executionPolicy().terminal() != null)
                        throw new IllegalArgumentException(
                                "This execution must complete through its configured terminal");
                    ToolCallContextHolder.installControl(
                            new ModelControl(
                                    context,
                                    request,
                                    toolEngine,
                                    invocation.whitelistedTools(),
                                    objectMapper,
                                    output.history(),
                                    invocation.request(),
                                    batch.modelCallId(),
                                    !batch.generation));
                }
            }
            // (e) plugin postAction chain
            lifecycle.checkTaskCancellation();
            boolean waitsForAnswer = def.capability() == ToolCapability.USER_INTERACTION;
            if (waitsForAnswer) lifecycle.saveExecutionWait(Wait.QUESTION);
            ToolResult transformed = toolEngine.execute(call, def);
            lifecycle.checkTaskCancellation();
            ToolResult actualResult = transformed;
            String hookContent = hooks.afterTool(call, actualResult);
            transformed = transformed.withContent(hookContent);
            for (LoopInterceptor plugin : interceptors) {
                transformed = plugin.postAction(agentId, call, transformed);
            }

            // (f) plugin observation transformations are untrusted input to the final defense.
            String pluginObservation = hooks.beforeObservation(transformed.content());
            for (LoopInterceptor plugin : interceptors) {
                pluginObservation = plugin.preObservation(agentId, pluginObservation);
            }
            transformed = transformed.withContent(pluginObservation);

            // (g) final ingress defense, immediately before committing the observation to history.
            String replacement = null;
            var eventManager = lifecycle.eventManager();
            String currentOwner = owner;
            if (transformed.success()
                    && def instanceof NativeToolDefinition
                    && def.capability() == ToolCapability.WORKSPACE_READ
                    && eventManager != null
                    && currentOwner != null) {
                var event =
                        new BeforeTextCommitEvent(
                                new Scope.AgentScope(currentOwner, sessionId.toString(), agentId),
                                () -> Thread.currentThread().isInterrupted(),
                                BeforeTextCommitEvent.Phase.FILE_OBSERVATION,
                                UUID.randomUUID().toString(),
                                transformed.content());
                eventManager.submit(event);
                if (event.isCancelled())
                    throw new IllegalStateException("File observation cancelled");
                if (event.replaced()) replacement = event.text();
            }
            String observation = toolBoundary.defend(authorized, transformed, replacement);

            ToolResult observed = transformed.withContent(observation);
            output.appendToolResponse(observed);
            var responseDirective = ToolCallContextHolder.drainResponse();
            if (responseDirective
                    instanceof ToolCallContextHolder.ResponseDirective.Await awaiting) {
                if (observed.success()
                        && awaiting.requestId().equals(invocation.request().episode.id()))
                    invocation.request().await(awaiting.signal(), lifecycle::signalWork);
                else awaiting.signal().ready().cancel(false);
            } else if (observed.success() && responseDirective != null)
                batch.control = responseDirective;
            if (waitsForAnswer && lifecycle.control().open()) lifecycle.saveExecutionWait(null);

            if (observed.success()) lifecycle.refreshConfiguration();
            return observed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @NonNull ToolResult executeOneCall(
            @NonNull ToolCall call, @NonNull ToolBatch batch, @NonNull Invocation invocation) {
        String callId = call.callId();
        if (!invocation.whitelistedTools().contains(call.toolName()))
            throw new SecurityException("Tool is not available in this role: " + call.toolName());
        ToolDefinition def = toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call, batch);
        }

        if (def instanceof LocalToolDefinition local)
            NativeToolArgumentValidator.validate(
                    local.name(), objectMapper.valueToTree(call.args()), local.argsClass());

        var event = hooks.beforeTool(call);
        if (event != null && event.isCancelled()) return cancelledCall(call, batch);
        var hookDecision = event == null ? BeforeToolEvent.Decision.CONTINUE : event.decision();
        ScreenedInvocation screened =
                toolBoundary.assess(
                        call,
                        def,
                        invocation.request().episode.task(),
                        null,
                        batch.step,
                        invocation.request().episode.id(),
                        hookDecision);
        ApprovalDecision decision = screened.decision();
        {
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                output.appendToolCall(call, batch.modelCallId());
                output.appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked: " + ab.reason(),
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
            if (decision instanceof ApprovalDecision.Refused r) {
                output.appendToolCall(call, batch.modelCallId());
                output.appendToolResponse(
                        call.toolName(),
                        call.callId(),
                        AgentToolExecution.refusedObservation(r.reason()),
                        false);
                throw new VetoRefusedException();
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                ToolCall resolvedCall = awaitVeto(screened, p, batch, invocation);
                if (resolvedCall == null) {
                    throw new VetoRefusedException(true);
                }
                lifecycle.transitionTo(AgentState.RUNNING);
            }
        }

        lifecycle.transitionTo(AgentState.WAITING);
        try {
            return executeResolvedCall(screened, batch, invocation);
        } finally {
            if (lifecycle.control().state() == AgentState.WAITING)
                lifecycle.transitionTo(AgentState.RUNNING);
        }
    }

    private @NonNull ToolResult cancelledCall(@NonNull ToolCall call, @NonNull ToolBatch batch) {
        var result = new ToolResult(
                call.toolName(), call.callId(), ToolResultStatus.REFUSED,
                ToolResultFormat.PLAINTEXT, refusedObservation("cancelled by a plugin listener"),
                ToolErrorCode.POLICY.CALL_BLOCKED);
        output.appendToolCall(call, batch.modelCallId());
        output.appendToolResponse(result);
        return result;
    }

    private @NonNull ToolResult toolNotFound(@NonNull ToolCall call, @NonNull ToolBatch batch) {
        String observation = "Tool not found: " + call.toolName();
        output.appendToolCall(call, batch.modelCallId());
        output.appendObservation(call.toolName(), observation);
        return ToolResult.failure(
                call.toolName(), call.callId(), observation, ToolErrorCode.VALIDATION.UNKNOWN_TOOL);
    }

    static @NonNull String refusedObservation(@NonNull String detail) {
        return RefusalObservation.of(detail);
    }

    private @NonNull String toolCallSignature(@NonNull ToolCall call) {
        try {
            return call.toolName() + '\u0000' + objectMapper.writeValueAsString(call.args());
        } catch (Exception e) {
            return call.toolName() + '\u0000' + call.args();
        }
    }

    private @NonNull InterceptResolution awaitResolution(
            @NonNull String callId, @NonNull Invocation invocation) {
        InterceptResolution resolution = toolBoundary.await(callId);
        synchronized (lifecycle) {
            // cancelTask must finish both declining the wait and interrupting this thread first.
            RequestHandle cancellation = lifecycle.control().request();
            boolean restoreInterrupt =
                    cancellation != null && cancellation.cancelled && Thread.interrupted();
            try {
                if (lifecycle.control().open()) lifecycle.saveExecutionWait(null);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
        lifecycle.checkTaskCancellation();
        invocation
                .request()
                .approvalReceipts
                .put(
                        callId,
                        new ApprovalReceipt(
                                resolution.option(),
                                resolution.source(),
                                Instant.now().toString()));
        output.publishFrame(
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

    private ToolCall awaitVeto(
            @NonNull ScreenedInvocation screened,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull ToolBatch batch,
            @NonNull Invocation invocation) {
        ToolCall call = screened.call();
        lifecycle.transitionTo(AgentState.INTERCEPTED);
        // Register before advertising the prompt so a fast reply cannot beat registration.
        List<VetoOption> offered = p.options();
        String callId = call.callId();
        toolBoundary.register(screened, offered, p.danger(), p.relevance());
        output.emitVetoRequired(call, p, offered);
        InterceptResolution resolution = awaitResolution(callId, invocation);
        lifecycle.transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            output.appendToolCall(call, batch.modelCallId());
            output.appendToolResponse(
                    call.toolName(),
                    call.callId(),
                    AgentToolExecution.refusedObservation(resolution.refusalReason()),
                    false);
            return null;
        }
        return call;
    }
}
