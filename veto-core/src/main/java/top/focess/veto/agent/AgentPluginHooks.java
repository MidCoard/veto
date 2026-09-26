package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.event.AfterModelEvent;
import top.focess.veto.api.event.AfterToolEvent;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.BeforeModelEvent;
import top.focess.veto.api.event.BeforeObservationEvent;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.event.ModelCall;
import top.focess.veto.api.event.WorkflowEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.plugin.runtime.PluginJson;

/**
 * Dispatches workflow events to selected plugin listeners and protects inputs at the host boundary.
 */
final class AgentPluginHooks {
    private final @Nullable String owner;
    private final @NonNull String sessionId;
    private final @NonNull String agentId;
    private final @NonNull Cancellation cancellation;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull Supplier<@Nullable SessionPlugins> plugins;
    private final @NonNull BooleanSupplier alive;
    private final @NonNull BooleanSupplier cancelled;

    AgentPluginHooks(
            @Nullable String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull ObjectMapper mapper,
            @NonNull UniformLLMCaller caller,
            @NonNull Supplier<@Nullable SessionPlugins> plugins,
            @NonNull BooleanSupplier alive,
            @NonNull BooleanSupplier cancelled) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.cancellation = cancellation;
        this.mapper = mapper;
        this.caller = caller;
        this.plugins = plugins;
        this.alive = alive;
        this.cancelled = cancelled;
    }

    private void checkCancellation() {
        if (cancelled.getAsBoolean()) {
            // Clear any pending interrupt so cleanup does not inherit the cancellation signal.
            AgentLifecycle.clearTaskInterrupt();
            throw new CancellationException("Task cancelled");
        }
    }

    /**
     * Dispatches one workflow event to the session's selected listeners, bracketed by cancellation.
     */
    private void dispatch(@NonNull WorkflowEvent event) {
        var selected = plugins.get();
        if (selected == null) return;
        checkCancellation();
        selected.dispatch(event);
        checkCancellation();
    }

    private BeforeToolEvent.@NonNull Invocation invocation(@NonNull ToolCall call) {
        return new BeforeToolEvent.Invocation(
                call.toolName(), call.callId(), PluginJson.object(mapper.valueToTree(call.args())));
    }

    /** Transforms user input before model processing. */
    @NonNull String beforeInput(@NonNull String text) {
        var event = new BeforeInputEvent(owner, sessionId, agentId, cancellation, text);
        dispatch(event);
        return event.text();
    }

    /** Evaluates a validated tool call before host authorization; the decision is monotonic. */
    BeforeToolEvent.@NonNull Decision beforeToolHooks(@NonNull ToolCall call) {
        var event = new BeforeToolEvent(owner, sessionId, agentId, cancellation, invocation(call));
        dispatch(event);
        return event.decision();
    }

    @NonNull VetoResponse callModelWithHooks(@NonNull VetoRequest request) {
        var model = new ModelCall(request.providerType().name(), request.modelName());
        var before = new BeforeModelEvent(owner, sessionId, agentId, cancellation, model);
        dispatch(before);
        if (before.isPrevent())
            throw new IllegalStateException("Model call prevented by plugin listener");
        VetoResponse response = caller.call(request);
        checkCancellation();
        var after =
                new AfterModelEvent(
                        owner, sessionId, agentId, cancellation, model, response.message());
        dispatch(after);
        return new VetoResponse(
                response.thought(), response.calls(), after.message(), response.citations());
    }

    /** Transforms the observation body while the host preserves execution status and format. */
    @NonNull String afterTool(@NonNull ToolCall call, @NonNull ToolResult result) {
        var event =
                new AfterToolEvent(
                        owner,
                        sessionId,
                        agentId,
                        cancellation,
                        invocation(call),
                        new AfterToolEvent.Output(result.format(), result.success()),
                        result.content());
        dispatch(event);
        return event.content();
    }

    /** Transforms ordinary observation text before publication. */
    @NonNull String beforeObservation(@NonNull String text) {
        var event = new BeforeObservationEvent(owner, sessionId, agentId, cancellation, text);
        dispatch(event);
        return event.text();
    }

    @NonNull List<ModelResponsePolicy.Exchange> responsePolicies() {
        var selected = plugins.get();
        return selected == null ? List.of() : selected.responsePolicies(sessionId);
    }

    @NonNull String captureUserPrompt(@NonNull String prompt) {
        var selected = plugins.get();
        if (selected == null) return prompt;
        if (!alive.getAsBoolean() || owner == null || owner.isBlank())
            throw new ProtectedInputException();
        try {
            return selected.protect(
                    StandardContributionPoints.INPUT_PROTECTION,
                    new TextProtection.Scope(owner, sessionId, agentId),
                    prompt);
        } catch (RuntimeException failure) {
            throw new ProtectedInputException();
        }
    }
}
