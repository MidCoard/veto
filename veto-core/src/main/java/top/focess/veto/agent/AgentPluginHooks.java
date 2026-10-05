package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
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
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.event.ModelCall;
import top.focess.veto.api.event.WorkflowEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.plugin.runtime.PluginJson;

/**
 * Dispatches workflow events to selected plugin listeners and protects inputs at the host boundary.
 */
final class AgentPluginHooks {
    private final Scope.@NonNull AgentScope scope;
    private final @NonNull String sessionId;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull Supplier<@Nullable SessionPlugins> plugins;
    private final @NonNull Supplier<@Nullable EventManager> events;
    private final @NonNull BooleanSupplier alive;
    private final @NonNull BooleanSupplier cancelled;

    AgentPluginHooks(
            @NonNull UUID userId,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull ObjectMapper mapper,
            @NonNull UniformLLMCaller caller,
            @NonNull Supplier<@Nullable SessionPlugins> plugins,
            @NonNull Supplier<@Nullable EventManager> events,
            @NonNull BooleanSupplier alive,
            @NonNull BooleanSupplier cancelled) {
        this.scope = new Scope.AgentScope(userId, sessionId, agentId);
        this.sessionId = sessionId;
        this.mapper = mapper;
        this.caller = caller;
        this.plugins = plugins;
        this.events = events;
        this.alive = alive;
        this.cancelled = cancelled;
    }

    private void checkCancellation() {
        if (cancelled.getAsBoolean()) {
            // Clear any pending interrupt so cleanup does not inherit the cancellation signal.
            AgentRunner.clearTaskInterrupt();
            throw new CancellationException("Task cancelled");
        }
    }

    /**
     * Dispatches one workflow event to the session's selected listeners, bracketed by cancellation.
     */
    private void dispatch(@NonNull EventManager manager, @NonNull WorkflowEvent event) {
        checkCancellation();
        var identity = event.scope();
        var invocation = new PluginInvocationContext(identity.userId(), identity.session());
        try {
            manager.submit(event);
        } finally {
            invocation.close();
        }
        checkCancellation();
    }

    private BeforeToolEvent.@NonNull Invocation invocation(@NonNull ToolCall call) {
        return new BeforeToolEvent.Invocation(
                call.toolName(), call.callId(), PluginJson.object(mapper.valueToTree(call.args())));
    }

    /** Transforms user input before model processing. */
    @NonNull String beforeInput(@NonNull String text) {
        var manager = events.get();
        if (manager == null) return text;
        var event = new BeforeInputEvent(scope, text);
        dispatch(manager, event);
        return event.text();
    }

    /** Returns the final tool event, or null when plugin delivery is detached. */
    BeforeToolEvent beforeTool(@NonNull ToolCall call) {
        var manager = events.get();
        if (manager == null) return null;
        var event = new BeforeToolEvent(scope, invocation(call));
        dispatch(manager, event);
        return event;
    }

    @NonNull VetoResponse callModelWithHooks(@NonNull VetoRequest request) {
        var manager = events.get();
        if (manager == null) {
            var response = caller.call(request, sessionId);
            checkCancellation();
            return response;
        }
        var identity = scope;
        var model = new ModelCall(request.providerType().name(), request.modelName());
        var before = new BeforeModelEvent(identity, model);
        dispatch(manager, before);
        if (before.isCancelled())
            throw new IllegalStateException("Model call cancelled by plugin listener");
        VetoResponse response = caller.call(request, sessionId);
        var after = new AfterModelEvent(identity, model, response.message());
        dispatch(manager, after);
        return new VetoResponse(
                response.thought(), response.calls(), after.message(), response.citations());
    }

    /** Transforms the observation body while the host preserves execution status and format. */
    @NonNull String afterTool(@NonNull ToolCall call, @NonNull ToolResult result) {
        var manager = events.get();
        if (manager == null) return result.content();
        var event =
                new AfterToolEvent(
                        scope,
                        invocation(call),
                        new AfterToolEvent.Output(result.format(), result.success()),
                        result.content());
        dispatch(manager, event);
        return event.content();
    }

    /** Transforms ordinary observation text before publication. */
    @NonNull String beforeObservation(@NonNull String text) {
        var manager = events.get();
        if (manager == null) return text;
        var event = new BeforeObservationEvent(scope, text);
        dispatch(manager, event);
        return event.text();
    }

    @NonNull List<ModelResponsePolicy.Exchange> responsePolicies() {
        var selected = plugins.get();
        return selected == null ? List.of() : selected.responsePolicies(sessionId);
    }

    @NonNull String captureUserPrompt(@NonNull String prompt) {
        var manager = events.get();
        if (manager == null) return prompt;
        if (!alive.getAsBoolean()) throw new ProtectedInputException();
        try {
            var event =
                    new BeforeTextCommitEvent(
                            scope,
                            BeforeTextCommitEvent.Phase.INPUT,
                            UUID.randomUUID().toString(),
                            prompt);
            dispatch(manager, event);
            if (event.isCancelled()) throw new ProtectedInputException();
            return event.text();
        } catch (RuntimeException failure) {
            throw new ProtectedInputException();
        }
    }
}
