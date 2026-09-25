package top.focess.veto.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.VetoRefusedException;
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
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.screening.Danger;
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

/** Screens tool batches, obtains approvals and executes under host-issued permits. */
final class AgentToolExecution {
    private final @NonNull AgentRuntimeState runtime;

    AgentToolExecution(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    ToolCallContextHolder.ResponseDirective executeToolCalls(
            @NonNull List<ToolCall> calls, String thought, @NonNull ToolBatch batch) {
        runtime.lifecycle().transitionTo(AgentState.WAITING);
        try {

            if (calls.size() > 1
                    && calls.stream()
                            .anyMatch(
                                    c -> runtime.responses.submissionKind(c.toolName()) != null)) {
                for (var call : calls) {
                    runtime.output().appendToolCall(call, batch.modelCallId());
                    runtime.output()
                            .appendToolResponse(
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
                if (runtime.lifecycle()
                        .currentRequest()
                        .declinedCallSignatures
                        .contains(toolCallSignature(call))) {
                    runtime.output().appendToolCall(call, batch.modelCallId());
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
                return batch.control;
            }
            calls = callsNeedingDecision;

            // 1. Check phase (screen all calls first)
            List<ApprovalDecision> decisions = new ArrayList<>();
            Map<String, ScreenedInvocation> screenedInvocations = new LinkedHashMap<>();
            boolean hasVeto = false;
            boolean hasRefused = false;
            for (ToolCall call : calls) {
                ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
                if (def == null) {
                    decisions.add(ApprovalDecision.AUTO_APPROVE);
                } else {
                    var hookDecision = runtime.hooks().beforeToolHooks(call);
                    ScreenedInvocation screened =
                            runtime.toolBoundary.assess(
                                    call,
                                    def,
                                    runtime.currentTask(),
                                    thought,
                                    batch.step,
                                    runtime.lifecycle().currentRequest().episode.id(),
                                    hookDecision);
                    screenedInvocations.put(call.callId(), screened);
                    ApprovalDecision decision = screened.decision();
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

                    if (runtime.lifecycle()
                            .currentRequest()
                            .declinedCallSignatures
                            .contains(toolCallSignature(call))) {
                        skippedCalls.add(call);
                    } else if (decision instanceof ApprovalDecision.Refused r) {
                        runtime.output().emitMessage(r.reason());
                        runtime.lifecycle().transitionTo(AgentState.INTERCEPTED);
                        if (def == null) {
                            throw new IllegalStateException("Refusal without a tool definition");
                        }
                        List<VetoOption> offered = List.of(VetoOption.EXEC_DECLINE);
                        ScreenedInvocation screened = screenedInvocations.get(callId);
                        if (screened == null)
                            throw new IllegalStateException("Missing screened invocation");
                        runtime.toolBoundary.register(screened, offered, Danger.CRITICAL, null);
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
                        ScreenedInvocation screened = screenedInvocations.get(callId);
                        if (screened == null)
                            throw new IllegalStateException("Missing screened invocation");
                        runtime.toolBoundary.register(screened, offered, p.danger(), p.relevance());
                        emitVetoRequired(call, p, offered);
                        InterceptResolution resolution = awaitResolution(callId);

                        if (resolution.option() == VetoOption.DECLINE_AND_CONTINUE) {
                            skippedCalls.add(call);
                            runtime.lifecycle()
                                    .currentRequest()
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
                        runtime.output().appendToolCall(call, batch.modelCallId());
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
            if (runtime.control.state() == AgentState.INTERCEPTED)
                runtime.lifecycle().transitionTo(AgentState.WAITING);
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (skippedCalls.contains(call)) {
                    runtime.output().appendToolCall(call, batch.modelCallId());
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
                    long configuration = runtime.configurationRevision;
                    executeOneConfirmedCall(call, screenedInvocations.get(call.callId()), batch);
                    if (batch.control != null) return batch.control;
                    if (runtime.configurationRevision != configuration) {
                        // Remaining calls were authored for the previous configuration.
                        return null;
                    }
                }
            }

        } finally {
            if (runtime.control.state() == AgentState.WAITING
                    || runtime.control.state() == AgentState.INTERCEPTED) {
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
            }
        }
        return batch.control;
    }

    @NonNull ToolResult executeOneConfirmedCall(
            @NonNull ToolCall call, ScreenedInvocation screened, @NonNull ToolBatch batch) {
        ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call, batch);
        }
        if (screened == null) throw new IllegalStateException("Missing screened invocation");
        return runtime.tools().executeResolvedCall(screened, batch);
    }

    @NonNull ToolResult executeResolvedCall(
            @NonNull ScreenedInvocation screened, @NonNull ToolBatch batch) {
        ToolCall call = screened.call();
        ToolDefinition def = screened.definition();
        runtime.lifecycle().checkExecutionBoundary();
        runtime.output().appendToolCall(call, batch.modelCallId());

        ToolExecutionPermit executionPermit;
        var authorized = runtime.toolBoundary.authorize(screened);
        try {
            executionPermit = runtime.toolBoundary.revalidate(authorized);
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

        // (d) execute with tool call context (agentId + userId + sessionId) threaded through.
        ToolCallContextHolder.set(
                new ToolCallContext(
                        runtime.agentId,
                        runtime.userId,
                        runtime.owner,
                        runtime.sessionId,
                        runtime.toolResultPresentation,
                        executionPermit.withCaller(
                                runtime.agentId, runtime.userId, runtime.owner, runtime.sessionId),
                        runtime.lifecycle().currentRequest().episode.id()));
        try {
            if (runtime.responses.submissionKind(call.toolName()) != null) {
                var exchange = batch.exchange;
                var request = exchange != null && exchange.accepted() ? exchange.request() : null;
                if (request != null
                        && request.nativeToolsEnabled()
                        && request.tools().stream()
                                .anyMatch(t -> t.name().equals(call.toolName()))) {
                    var context = ToolCallContextHolder.get();
                    if (context == null)
                        throw new IllegalStateException("Missing admitted invocation");
                    if (runtime.executionPolicy.terminal() != null)
                        throw new IllegalArgumentException(
                                "This execution must complete through its configured terminal");
                    ToolCallContextHolder.installControl(
                            new ModelControl(
                                    context,
                                    request,
                                    runtime.toolEngine,
                                    runtime.whitelistedTools,
                                    runtime.objectMapper,
                                    runtime.output().history(),
                                    runtime.output().requestIdentity(),
                                    batch.modelCallId(),
                                    !batch.generation));
                }
            }
            // (e) plugin postAction chain
            runtime.lifecycle().checkTaskCancellation();
            boolean waitsForAnswer = def.capability() == ToolCapability.USER_INTERACTION;
            if (waitsForAnswer) runtime.lifecycle().saveExecutionWait(Wait.QUESTION);
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
            String protectedText = null;
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
                protectedText =
                        selected.protect(
                                StandardContributionPoints.FILE_OBSERVATION,
                                new TextProtection.Scope(
                                        currentOwner,
                                        runtime.sessionId.toString(),
                                        runtime.agentId),
                                transformed.content());
            }
            String observation =
                    runtime.toolBoundary.defend(authorized, transformed, protectedText);

            ToolResult observed = transformed.withContent(observation);
            runtime.output().appendToolResponse(observed);
            var responseDirective = ToolCallContextHolder.drainResponse();
            if (responseDirective
                    instanceof ToolCallContextHolder.ResponseDirective.Await awaiting) {
                if (observed.success()
                        && awaiting.requestId()
                                .equals(runtime.lifecycle().currentRequest().episode.id()))
                    runtime.lifecycle()
                            .currentRequest()
                            .await(awaiting.signal(), runtime.continuations()::signalWork);
                else awaiting.signal().ready().cancel(false);
            } else if (observed.success() && responseDirective != null)
                batch.control = responseDirective;
            if (waitsForAnswer && runtime.control.open())
                runtime.lifecycle().saveExecutionWait(null);

            if (observed.success()) runtime.lifecycle().refreshConfiguration();
            return observed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @NonNull ToolResult executeOneCall(@NonNull ToolCall call, @NonNull ToolBatch batch) {
        String callId = call.callId();
        if (!runtime.whitelistedTools.contains(call.toolName()))
            throw new SecurityException("Tool is not available in this role: " + call.toolName());
        ToolDefinition def = runtime.toolEngine.resolveDefinition(call.toolName());
        if (def == null) {
            return toolNotFound(call, batch);
        }

        if (def instanceof LocalToolDefinition local)
            NativeToolArgumentValidator.validate(
                    local.name(), runtime.objectMapper.valueToTree(call.args()), local.argsClass());

        var hookDecision = runtime.hooks().beforeToolHooks(call);
        ScreenedInvocation screened =
                runtime.toolBoundary.assess(
                        call,
                        def,
                        runtime.currentTask(),
                        null,
                        batch.step,
                        runtime.lifecycle().currentRequest().episode.id(),
                        hookDecision);
        ApprovalDecision decision = screened.decision();
        {
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                runtime.output().appendToolCall(call, batch.modelCallId());
                runtime.output().appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return ToolResult.failure(
                        call.toolName(),
                        call.callId(),
                        "blocked: " + ab.reason(),
                        ToolErrorCode.POLICY.CALL_BLOCKED);
            }
            if (decision instanceof ApprovalDecision.Refused r) {
                runtime.output().appendToolCall(call, batch.modelCallId());
                runtime.output()
                        .appendToolResponse(
                                call.toolName(),
                                call.callId(),
                                AgentToolExecution.refusedObservation(r.reason()),
                                false);
                throw new VetoRefusedException();
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                ToolCall resolvedCall = awaitVeto(screened, p, batch);
                if (resolvedCall == null) {
                    throw new VetoRefusedException(true);
                }
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
            }
        }

        runtime.lifecycle().transitionTo(AgentState.WAITING);
        try {
            return executeResolvedCall(screened, batch);
        } finally {
            if (runtime.control.state() == AgentState.WAITING)
                runtime.lifecycle().transitionTo(AgentState.RUNNING);
        }
    }

    @NonNull ToolResult toolNotFound(@NonNull ToolCall call, @NonNull ToolBatch batch) {
        String observation = "Tool not found: " + call.toolName();
        runtime.output().appendToolCall(call, batch.modelCallId());
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
        InterceptResolution resolution = runtime.toolBoundary.await(callId);
        synchronized (runtime) {
            // cancelTask must finish both declining the wait and interrupting this thread first.
            RequestHandle cancellation = runtime.control.request();
            boolean restoreInterrupt =
                    cancellation != null && cancellation.cancelled && Thread.interrupted();
            try {
                if (runtime.control.open()) runtime.lifecycle().saveExecutionWait(null);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
        runtime.lifecycle().checkTaskCancellation();
        runtime.lifecycle()
                .currentRequest()
                .approvalReceipts
                .put(
                        callId,
                        new ApprovalReceipt(
                                resolution.option(),
                                resolution.source(),
                                Instant.now().toString()));
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

    ToolCall awaitVeto(
            @NonNull ScreenedInvocation screened,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull ToolBatch batch) {
        ToolCall call = screened.call();
        runtime.lifecycle().transitionTo(AgentState.INTERCEPTED);
        // Register before advertising the prompt so a fast reply cannot beat registration.
        List<VetoOption> offered = p.options();
        String callId = call.callId();
        runtime.toolBoundary.register(screened, offered, p.danger(), p.relevance());
        emitVetoRequired(call, p, offered);
        InterceptResolution resolution = awaitResolution(callId);
        runtime.lifecycle().transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            runtime.output().appendToolCall(call, batch.modelCallId());
            runtime.output()
                    .appendToolResponse(
                            call.toolName(),
                            call.callId(),
                            AgentToolExecution.refusedObservation(resolution.refusalReason()),
                            false);
            return null;
        }
        return call;
    }

    void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered) {
        runtime.output().emitVetoRequired(call, p, offered);
    }
}
