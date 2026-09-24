package top.focess.veto.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.ProcessInputTarget;
import top.focess.veto.agent.AgentRuntimeState.ResolvedCall;
import top.focess.veto.agent.AgentRuntimeState.TaskCancellation;
import top.focess.veto.agent.AgentRuntimeState.VetoRefusedException;
import top.focess.veto.agent.AgentRuntimeState.WaitReason;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.RefusalObservation;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.sandbox.BackgroundTaskManager;

/** Screens tool batches, obtains approvals and executes under host-issued permits. */
final class AgentToolExecution {
    private final @NonNull AgentRuntimeState runtime;

    AgentToolExecution(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void executeToolCalls(@NonNull List<ToolCall> calls, String thought) {
        runtime.lifecycle().transitionTo(AgentState.WAITING);
        runtime.currentToolModelCallId = runtime.lastModelCallId;
        try {

            if (calls.size() > 1
                    && calls.stream()
                            .anyMatch(c -> runtime.models().submissionKind(c.toolName()) != null)) {
                for (var call : calls) {
                    runtime.output().appendToolCall(call);
                    runtime.output()
                            .appendToolResponse(
                                    call.toolName(),
                                    call.callId(),
                                    "A response submission must be the only tool call. No calls in this"
                                            + " batch were executed; resubmit separately.",
                                    false);
                }
                return;
            }
            List<ToolCall> callsNeedingDecision = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                if (runtime.declinedCallSignatures.contains(toolCallSignature(call))) {
                    runtime.output().appendToolCall(call);
                    runtime.output()
                            .appendToolResponse(
                                    call.toolName(),
                                    call.callId(),
                                    AgentToolExecution.refusedObservation(
                                            PromptCompiler.compileText(
                                                    "runtime-refused-duplicate")),
                                    false);
                } else {
                    callsNeedingDecision.add(call);
                }
            }
            if (callsNeedingDecision.isEmpty()) {
                return;
            }
            calls = callsNeedingDecision;

            // 1. Check phase (screen all calls first)
            List<ApprovalDecision> decisions = new ArrayList<>();
            List<ToolExecutionPermit> executionPermits = new ArrayList<>();
            boolean hasVeto = false;
            boolean hasRefused = false;
            for (ToolCall call : calls) {
                ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
                if (def == null) {
                    decisions.add(ApprovalDecision.AUTO_APPROVE);
                    executionPermits.add(ToolExecutionPermit.empty());
                } else {
                    var hookDecision = runtime.hooks().beforeToolHooks(call);
                    var result =
                            def instanceof AgentToolDefinition
                                    ? new GatewayResult.NotScreened()
                                    : screenToolCall(call, def, thought);
                    executionPermits.add(result.executionPermit());
                    ApprovalDecision decision =
                            runtime.hitlRegistry.decide(
                                    runtime.agentId, call, def, result, hookDecision);
                    decisions.add(decision);
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
                    ApprovalDecision decision = decisions.get(i);
                    ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());

                    if (runtime.declinedCallSignatures.contains(toolCallSignature(call))) {
                        skippedCalls.add(call);
                    } else if (decision instanceof ApprovalDecision.Refused r) {
                        runtime.output().emitMessage(r.reason());
                        runtime.lifecycle().transitionTo(AgentState.INTERCEPTED);
                        if (def == null) {
                            throw new IllegalStateException("Refusal without a tool definition");
                        }
                        List<VetoOption> offered = List.of(VetoOption.EXEC_DECLINE);
                        runtime.hitlRegistry.register(
                                runtime.agentId, callId, call, def, offered, Danger.CRITICAL, null);
                        runtime.tools()
                                .emitVetoRequired(
                                        call,
                                        new ApprovalDecision.Prompt(
                                                VetoScenario.GENERIC,
                                                offered,
                                                Danger.CRITICAL,
                                                null),
                                        offered);
                        awaitResolution(callId);
                        refusalDetail =
                                "refused by the security policy (CRITICAL - no approval path)";
                        batchApproved = false;
                        break;
                    } else if (decision instanceof ApprovalDecision.Prompt p) {
                        runtime.lifecycle().transitionTo(AgentState.INTERCEPTED);
                        // Register the await target BEFORE advertising the prompt: the veto
                        // listener sends the Prompt synchronously, and the user's reply could
                        // otherwise race register and resolve against a not-yet-registered future.
                        List<VetoOption> offered = p.options();
                        if (def == null) {
                            throw new IllegalStateException(
                                    "Prompt decision without a tool definition for "
                                            + call.toolName());
                        }
                        runtime.hitlRegistry.register(
                                runtime.agentId,
                                callId,
                                call,
                                def,
                                offered,
                                p.danger(),
                                p.relevance());
                        emitVetoRequired(call, p, offered);
                        InterceptResolution resolution = awaitResolution(callId);

                        if (resolution.option() == VetoOption.DECLINE_AND_CONTINUE) {
                            skippedCalls.add(call);
                            runtime.declinedCallSignatures.add(toolCallSignature(call));
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
                        runtime.output().appendToolCall(call);
                        runtime.output()
                                .appendToolResponse(
                                        call.toolName(),
                                        call.callId(),
                                        AgentToolExecution.refusedObservation(refusalDetail),
                                        false);
                    }
                    runtime.lifecycle().transitionTo(AgentState.IDLE);
                    throw new VetoRefusedException(approvalRequested);
                }
            }

            // 3. Execute phase (all confirmed / skipped)
            if (runtime.state == AgentState.INTERCEPTED)
                runtime.lifecycle().transitionTo(AgentState.WAITING);
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (skippedCalls.contains(call)) {
                    runtime.output().appendToolCall(call);
                    runtime.output()
                            .appendToolResponse(
                                    call.toolName(),
                                    call.callId(),
                                    AgentToolExecution.refusedObservation(
                                                    "declined by the client (DECLINE_AND_CONTINUE)")
                                            + " Continue without this call: do not retry it"
                                            + " unchanged - pick a different approach, or explain"
                                            + " the blockage and stop.",
                                    false);
                } else {
                    AgentPersona callPersona = runtime.persona;
                    ToolResult result = executeOneConfirmedCall(call, executionPermits.get(i));
                    if (result.success() && call.toolName().equals(runtime.completionTool)) {
                        runtime.lastMessage = result.content();
                        runtime.completionToolFinished = true;
                        return;
                    }
                    if (runtime.pendingResponse != null) return;
                    if (runtime.persona != callPersona) {
                        return; // Remaining calls were authored for the previous role.
                    }
                }
            }

        } finally {
            runtime.currentToolModelCallId = null;
            if (runtime.state == AgentState.WAITING || runtime.state == AgentState.INTERCEPTED) {
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
            }
        }
    }

    @NonNull ToolResult executeOneConfirmedCall(
            @NonNull ToolCall call, @NonNull ToolExecutionPermit executionPermit) {
        ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call);
        }
        return runtime.tools()
                .executeResolvedCall(call, def, ApprovalDecision.AUTO_APPROVE, executionPermit);
    }

    @NonNull ToolResult executeResolvedCall(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            @NonNull ApprovalDecision decision,
            @NonNull ToolExecutionPermit screenedPermit) {
        runtime.lifecycle().checkExecutionBoundary();
        runtime.output().appendToolCall(call);

        ToolExecutionPermit executionPermit;
        try {
            executionPermit = runtime.gateway.revalidateExecution(call, def, screenedPermit);
        } catch (SecurityException e) {
            String observation =
                    "Filesystem target changed after screening; submit a fresh tool call";
            runtime.output().appendToolResponse(call.toolName(), call.callId(), observation, false);
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    observation,
                    ToolErrorCode.WORKSPACE.TREE_CHANGED);
        }

        // (c) plugin preAction chain
        for (LoopInterceptor plugin : runtime.interceptors) {
            if (!plugin.preAction(runtime.agentId, call)) {
                runtime.output().appendObservation(call.toolName(), "Blocked by plugin.");
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked by plugin",
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(
                new ToolCallContext(
                        runtime.agentId,
                        runtime.userId,
                        runtime.groupId,
                        runtime.owner,
                        runtime.sessionId,
                        runtime.toolResultPresentation,
                        executionPermit.withCaller(
                                runtime.agentId,
                                runtime.userId,
                                runtime.groupId,
                                runtime.owner,
                                runtime.sessionId),
                        runtime.activeRequestId));
        try {
            if (runtime.models().submissionKind(call.toolName()) != null) {
                var request = runtime.submissionRequest;
                if (request != null
                        && request.nativeToolsEnabled()
                        && request.tools().stream().anyMatch(t -> t.name().equals(call.toolName())))
                    ToolCallContextHolder.setResponseHandler(runtime.models()::validateSubmission);
            }
            // (e) plugin postAction chain
            runtime.lifecycle().checkTaskCancellation();
            boolean waitsForAnswer = def.capability() == ToolCapability.USER_INTERACTION;
            if (waitsForAnswer) runtime.lifecycle().saveExecutionWait(WaitReason.QUESTION);
            ToolResult transformed = runtime.toolEngine.execute(call, def);
            runtime.lifecycle().checkTaskCancellation();
            ToolResult actualResult = transformed;
            String hookContent =
                    runtime.hooks()
                            .workflow(
                                    actualResult.content(),
                                    (hook, content) ->
                                            hook.afterTool(
                                                    runtime.hooks().workflowContext(),
                                                    runtime.hooks().hookInvocation(call),
                                                    new WorkflowHook.Output(
                                                            content,
                                                            actualResult.format(),
                                                            actualResult.success())));
            transformed = transformed.withContent(hookContent);
            for (LoopInterceptor plugin : runtime.interceptors) {
                transformed = plugin.postAction(runtime.agentId, call, transformed);
            }

            // (f) plugin observation transformations are untrusted input to the final defense.
            String pluginObservation =
                    runtime.hooks()
                            .workflow(
                                    transformed.content(),
                                    (hook, text) ->
                                            hook.beforeObservation(
                                                    runtime.hooks().workflowContext(), text));
            for (LoopInterceptor plugin : runtime.interceptors) {
                pluginObservation = plugin.preObservation(runtime.agentId, pluginObservation);
            }
            transformed = transformed.withContent(pluginObservation);

            // (g) final ingress defense, immediately before committing the observation to history.
            String observation;
            var selected = runtime.sessionPlugins;
            String currentOwner = runtime.owner;
            if (transformed.success()
                    && def instanceof NativeToolDefinition
                    && def.capability() == ToolCapability.WORKSPACE_READ
                    && selected != null
                    && currentOwner != null
                    && selected.has(
                            runtime.sessionId.toString(),
                            StandardContributionPoints.FILE_OBSERVATION)) {
                // The owning plugin masks plain segments while preserving SECRET_REF markers.
                String protectedText =
                        selected.protect(
                                StandardContributionPoints.FILE_OBSERVATION,
                                new TextProtection.Scope(
                                        currentOwner,
                                        runtime.sessionId.toString(),
                                        runtime.agentId),
                                transformed.content());
                observation =
                        runtime.ingressDefense.frameProtectedFile(
                                call, def, transformed, protectedText);
            } else {
                observation =
                        runtime.ingressDefense.maskAndFrame(
                                call, def, transformed, decision, runtime.readHistory);
            }

            ToolResult observed = transformed.withContent(observation);
            runtime.output().appendToolResponse(observed);
            var responseDirective = ToolCallContextHolder.drainResponse();
            if (observed.success() && responseDirective != null)
                runtime.pendingResponse = responseDirective;
            if (waitsForAnswer && runtime.sessionAlive) runtime.lifecycle().saveExecutionWait(null);

            // Drain any turn directives the tool requested during execution (e.g. a REWIND seeded
            // by create_group to re-inject the authored brief). Each is appended with a
            // runner-assigned turn number; the pending record's placeholder turnNumber is rewritten
            // (type + payload preserved). Drained here, before clear() in the finally, so a tool
            // that threw never leaks a directive to the next call on this thread.
            for (TurnRecord pending : ToolCallContextHolder.drainPendingTurns()) {
                runtime.output()
                        .appendTurn(
                                new TurnRecord(
                                        ++runtime.turnNumber,
                                        pending.type(),
                                        pending.payload(),
                                        null));
            }
            // A tool may request a delegation transform (create_group) or its reverse
            // (disband_group).
            // Apply it after the pending turn directives: a forward transform's REWIND discards
            // this
            // call's response + prior turns, then re-seeds the Leader; a reverse transform restores
            // the STANDALONE persona. Drained before clear() in the finally so a throwing tool
            // leaks
            // no transform to the next call on this thread.
            ToolCallContextHolder.TransformRequest transformRequest =
                    ToolCallContextHolder.drainTransform();
            if (transformRequest instanceof ToolCallContextHolder.TransformRequest.ToLeader t) {
                runtime.lifecycle().transformToLeader(t.directive());
            } else if (transformRequest
                    instanceof ToolCallContextHolder.TransformRequest.ToStandalone t) {
                runtime.lifecycle().transformToStandalone(t.brief());
            }
            return observed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @NonNull ToolResult executeOneCall(@NonNull ToolCall call) {
        String callId = call.callId();
        if (!runtime.whitelistedTools.contains(call.toolName()))
            throw new SecurityException("Tool is not available in this role: " + call.toolName());
        ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call);
        }

        if (def instanceof LocalToolDefinition local)
            NativeToolArgumentValidator.validate(
                    local.name(), runtime.objectMapper.valueToTree(call.args()), local.argsClass());

        var hookDecision = runtime.hooks().beforeToolHooks(call);
        var result =
                def instanceof AgentToolDefinition
                        ? new GatewayResult.NotScreened()
                        : screenToolCall(call, def, null);
        ToolExecutionPermit executionPermit = result.executionPermit();
        ApprovalDecision decision =
                runtime.hitlRegistry.decide(runtime.agentId, call, def, result, hookDecision);
        {
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                runtime.output().appendToolCall(call);
                runtime.output().appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked: " + ab.reason(),
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
            if (decision instanceof ApprovalDecision.Refused r) {
                runtime.output().appendToolCall(call);
                runtime.output()
                        .appendToolResponse(
                                call.toolName(),
                                call.callId(),
                                AgentToolExecution.refusedObservation(r.reason()),
                                false);
                throw new VetoRefusedException();
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                ResolvedCall resolvedCall = awaitVeto(call, def, p, executionPermit);
                if (resolvedCall == null) {
                    throw new VetoRefusedException(true);
                }
                call = resolvedCall.call();
                executionPermit = resolvedCall.executionPermit();
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
            }
        }

        runtime.lifecycle().transitionTo(AgentState.WAITING);
        try {
            return executeResolvedCall(call, def, decision, executionPermit);
        } finally {
            if (runtime.state == AgentState.WAITING)
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
        }
    }

    @NonNull ToolResult toolNotFound(@NonNull ToolCall call) {
        String observation = "Tool not found: " + call.toolName();
        runtime.output().appendToolCall(call);
        runtime.output().appendObservation(call.toolName(), observation);
        return ToolResult.failure(
                call.toolName(), call.callId(), observation, ToolErrorCode.VALIDATION.UNKNOWN_TOOL);
    }

    static @NonNull String refusedObservation(@NonNull String detail) {
        return RefusalObservation.of(detail);
    }

    @NonNull String toolCallSignature(@NonNull ToolCall call) {
        try {
            return call.toolName()
                    + '\u0000'
                    + runtime.objectMapper.writeValueAsString(call.args());
        } catch (Exception e) {
            return call.toolName() + '\u0000' + call.args();
        }
    }

    @NonNull InterceptResolution awaitResolution(@NonNull String callId) {
        InterceptResolution resolution = runtime.hitlRegistry.await(runtime.agentId, callId);
        synchronized (runtime) {
            // cancelTask must finish both declining the wait and interrupting this thread first.
            TaskCancellation cancellation = runtime.activeCancellation;
            boolean restoreInterrupt =
                    cancellation != null && cancellation.cancelled && Thread.interrupted();
            try {
                if (runtime.sessionAlive) runtime.lifecycle().saveExecutionWait(null);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
        runtime.lifecycle().checkTaskCancellation();
        runtime.approvalReceipts.put(
                callId,
                new ApprovalReceipt(
                        resolution.option(), resolution.source(), Instant.now().toString()));
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.VETO_RESOLVED)
                                .attr("agentId", runtime.agentId)
                                .attr("callId", callId)
                                .attr("option", resolution.option().name())
                                .attr("refusal", resolution.isRefusal())
                                .build());
        return resolution;
    }

    ResolvedCall awaitVeto(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull ToolExecutionPermit executionPermit) {
        runtime.lifecycle().transitionTo(AgentState.INTERCEPTED);
        // Register before advertising the prompt so a fast reply cannot beat registration.
        List<VetoOption> offered = p.options();
        String callId = call.callId();
        runtime.hitlRegistry.register(
                runtime.agentId, callId, call, def, offered, p.danger(), p.relevance());
        emitVetoRequired(call, p, offered);
        InterceptResolution resolution = awaitResolution(callId);
        runtime.lifecycle().transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            runtime.output().appendToolCall(call);
            runtime.output()
                    .appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            AgentToolExecution.refusedObservation(resolution.refusalReason()),
                            false);
            return null;
        }
        return new ResolvedCall(call, executionPermit);
    }

    @NonNull GatewayResult screenToolCall(
            @NonNull ToolCall call, @NonNull ToolDefinition definition, String thought) {
        ProcessInputTarget processInput = processInputTarget(call, definition);
        GatewayResult result =
                runtime.gateway.screen(
                        call,
                        definition,
                        runtime.activeUserTask,
                        thought,
                        processInput == null ? null : processInput.screeningContext(),
                        runtime.currentPlanStep);
        if (processInput == null) {
            return result;
        }
        ToolExecutionPermit permit =
                result.executionPermit().withTaskBinding(processInput.binding());
        return switch (result) {
            case GatewayResult.Screened screened ->
                    new GatewayResult.Screened(screened.screening(), permit);
            case GatewayResult.DriftResult drift ->
                    new GatewayResult.DriftResult(drift.path(), drift.diff(), permit);
            case GatewayResult.NotScreened ignored -> result;
        };
    }

    ProcessInputTarget processInputTarget(
            @NonNull ToolCall call, @NonNull ToolDefinition definition) {
        if (!(definition instanceof NativeToolDefinition nativeDefinition)
                || !nativeDefinition.paramHints().containsValue(ParamCategory.PROCESS_INPUT)
                || runtime.backgroundTaskManager == null) {
            return null;
        }
        Object rawTaskId = call.args().get("taskId");
        if (!(rawTaskId instanceof String taskId) || taskId.isBlank()) {
            return null;
        }
        BackgroundTaskManager.InputTaskSnapshot snapshot =
                runtime.backgroundTaskManager
                        .inputTaskSnapshot(runtime.agentId, runtime.sessionId, taskId)
                        .orElse(null);
        if (snapshot == null) {
            return new ProcessInputTarget(
                    new ToolExecutionPermit.TaskBinding(
                            taskId, runtime.agentId, runtime.sessionId, new UUID(0, 0)),
                    "No background task with this id exists in the calling agent and session.");
        }
        return new ProcessInputTarget(
                new ToolExecutionPermit.TaskBinding(
                        snapshot.taskId(),
                        snapshot.agentId(),
                        snapshot.sessionId(),
                        snapshot.taskInstanceId()),
                "Target background process: executable="
                        + snapshot.command().executable()
                        + ", argv="
                        + snapshot.command().args()
                        + ", cwd="
                        + snapshot.cwd()
                        + ", networkAllowed="
                        + snapshot.networkAllowed()
                        + ", alive="
                        + snapshot.alive()
                        + ", stdinAvailable="
                        + snapshot.stdinAvailable());
    }

    void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered) {
        runtime.events.emitVetoRequired(call, p, offered);
    }
}
