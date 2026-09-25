package top.focess.veto.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.WorkflowHook;

/** Owns the host authority sequence around every tool invocation. */
public final class ToolExecutionBoundary {
    static final class ScreenedInvocation {
        private final @NonNull ToolCall call;
        private final @NonNull ToolDefinition definition;
        private final @NonNull ApprovalDecision decision;
        private final @NonNull ToolExecutionPermit permit;

        private ScreenedInvocation(
                @NonNull ToolCall call,
                @NonNull ToolDefinition definition,
                @NonNull ApprovalDecision decision,
                @NonNull ToolExecutionPermit permit) {
            this.call = call;
            this.definition = definition;
            this.decision = decision;
            this.permit = permit;
        }

        @NonNull ToolCall call() {
            return call;
        }

        @NonNull ToolDefinition definition() {
            return definition;
        }

        @NonNull ApprovalDecision decision() {
            return decision;
        }
    }

    static final class AuthorizedInvocation {
        private final @NonNull ScreenedInvocation screened;

        private AuthorizedInvocation(@NonNull ScreenedInvocation screened) {
            this.screened = screened;
        }
    }

    private final @NonNull String agentId;
    private final @NonNull UUID sessionId;
    private final String owner;
    private final @NonNull ToolEngine tools;
    private final @NonNull Gateway gateway;
    private final @NonNull HitlRegistry hitl;
    private final @NonNull IngressDefense ingress;
    private final @NonNull Map<String, ScreenedInvocation> pending = new ConcurrentHashMap<>();
    private final @NonNull Set<ScreenedInvocation> approved = ConcurrentHashMap.newKeySet();

    public ToolExecutionBoundary(
            @NonNull String agentId,
            @NonNull UUID sessionId,
            String owner,
            @NonNull ToolEngine tools,
            @NonNull Gateway gateway,
            @NonNull HitlRegistry hitl,
            @NonNull IngressDefense ingress) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.owner = owner;
        this.tools = tools;
        this.gateway = gateway;
        this.hitl = hitl;
        this.ingress = ingress;
        hitl.setSession(agentId, sessionId);
    }

    @NonNull ScreenedInvocation assess(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull String task,
            String thought,
            ActionContext step,
            @NonNull String requestId,
            WorkflowHook.@NonNull Decision hookDecision) {
        var invocation =
                new PluginHost.Invocation(
                        owner == null ? "" : owner,
                        sessionId.toString(),
                        agentId,
                        requestId,
                        call.callId());
        var prepared = tools.prepare(call, definition, invocation);
        GatewayResult screened =
                gateway.screen(call, definition, task, thought, null, step, prepared);
        ApprovalDecision decision = hitl.decide(agentId, call, definition, screened, hookDecision);
        return new ScreenedInvocation(call, definition, decision, screened.executionPermit());
    }

    void register(
            @NonNull ScreenedInvocation screened,
            @NonNull List<VetoOption> offered,
            Danger danger,
            Relevance relevance) {
        pending.put(screened.call.callId(), screened);
        hitl.register(
                agentId,
                screened.call.callId(),
                screened.call,
                screened.definition,
                offered,
                danger,
                relevance);
    }

    @NonNull InterceptResolution await(@NonNull String callId) {
        InterceptResolution resolution = hitl.await(agentId, callId);
        ScreenedInvocation screened = pending.remove(callId);
        if (screened != null
                && !resolution.isRefusal()
                && resolution.option() != VetoOption.DECLINE_AND_CONTINUE) approved.add(screened);
        return resolution;
    }

    @NonNull AuthorizedInvocation authorize(@NonNull ScreenedInvocation screened) {
        if (screened.decision() instanceof ApprovalDecision.AutoApprove) {
            return new AuthorizedInvocation(screened);
        }
        if (screened.decision() instanceof ApprovalDecision.Prompt && approved.remove(screened)) {
            return new AuthorizedInvocation(screened);
        }
        throw new SecurityException("Tool invocation has not completed host authorization");
    }

    @NonNull ToolExecutionPermit revalidate(@NonNull AuthorizedInvocation authorized) {
        ScreenedInvocation screened = authorized.screened;
        return gateway.revalidateExecution(screened.call, screened.definition, screened.permit);
    }

    @NonNull String defend(
            @NonNull AuthorizedInvocation authorized,
            @NonNull ToolResult result,
            String protectedText) {
        ScreenedInvocation screened = authorized.screened;
        if (protectedText != null
                && result.success()
                && screened.definition.capability() == ToolCapability.WORKSPACE_READ) {
            return ingress.frameProtectedFile(
                    screened.call, screened.definition, result, protectedText);
        }
        return ingress.maskAndFrame(
                screened.call,
                screened.definition,
                result,
                screened.decision(),
                gateway.readHistory());
    }

    @NonNull ReadHistory readHistory() {
        return gateway.readHistory();
    }

    @NonNull Workspace workspace() {
        return gateway.workspace();
    }

    void locale(@NonNull Locale locale) {
        hitl.setLocale(agentId, locale);
    }

    void declineAll() {
        pending.clear();
        approved.clear();
        hitl.declineAll(agentId);
    }

    void clear() {
        pending.clear();
        approved.clear();
        hitl.clear(agentId);
    }
}
