package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.plugin.runtime.PluginJson;

/** Invokes selected plugin hooks and protects inputs at the host boundary. */
final class AgentPluginHooks {
    private final WorkflowHook.@NonNull Context context;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull Supplier<@Nullable SessionPlugins> plugins;
    private final @NonNull BooleanSupplier alive;
    private final @NonNull BooleanSupplier cancelled;

    AgentPluginHooks(
            WorkflowHook.@NonNull Context context,
            @NonNull ObjectMapper mapper,
            @NonNull UniformLLMCaller caller,
            @NonNull Supplier<@Nullable SessionPlugins> plugins,
            @NonNull BooleanSupplier alive,
            @NonNull BooleanSupplier cancelled) {
        this.context = context;
        this.mapper = mapper;
        this.caller = caller;
        this.plugins = plugins;
        this.alive = alive;
        this.cancelled = cancelled;
    }

    WorkflowHook.@NonNull Context workflowContext() {
        return context;
    }

    @SuppressWarnings(
            "IgnoreResultOfCall") // WHY: interrupt flag is cleared deliberately before signalling
    // cancellation
    private void checkCancellation() {
        if (cancelled.getAsBoolean()) {
            Thread.interrupted();
            throw new CancellationException("Task cancelled");
        }
    }

    <T extends @NonNull Object> T workflow(
            T initial, SessionPlugins.@NonNull WorkflowOperation<T> operation) {
        var selected = plugins.get();
        if (selected == null
                || !selected.has(context.sessionId(), StandardContributionPoints.WORKFLOW))
            return initial;
        checkCancellation();
        T result = selected.workflow(workflowContext(), initial, operation);
        checkCancellation();
        return result;
    }

    WorkflowHook.@NonNull Invocation hookInvocation(@NonNull ToolCall call) {
        return new WorkflowHook.Invocation(
                call.toolName(), call.callId(), PluginJson.object(mapper.valueToTree(call.args())));
    }

    WorkflowHook.@NonNull Decision beforeToolHooks(@NonNull ToolCall call) {
        return workflow(
                WorkflowHook.Decision.CONTINUE,
                (hook, previous) -> {
                    if (previous == WorkflowHook.Decision.REJECT) return previous;
                    var next = hook.beforeTool(workflowContext(), hookInvocation(call));
                    return next.ordinal() > previous.ordinal() ? next : previous;
                });
    }

    @NonNull VetoResponse callModelWithHooks(@NonNull VetoRequest request) {
        var model = new WorkflowHook.ModelCall(request.providerType().name(), request.modelName());
        workflow(
                model,
                (hook, current) -> {
                    hook.beforeModel(workflowContext(), current);
                    return current;
                });
        VetoResponse response = caller.call(request);
        checkCancellation();
        var transformed =
                workflow(
                        new WorkflowHook.ModelOutput(response.message()),
                        (hook, output) -> hook.afterModel(workflowContext(), model, output));
        return new VetoResponse(
                response.thought(), response.calls(), transformed.message(), response.citations());
    }

    @NonNull List<ModelResponsePolicy.Exchange> responsePolicies() {
        var selected = plugins.get();
        return selected == null ? List.of() : selected.responsePolicies(context.sessionId());
    }

    @NonNull String captureUserPrompt(@NonNull String prompt) {
        var selected = plugins.get();
        if (selected == null) return prompt;
        String currentOwner = context.owner();
        if (!alive.getAsBoolean() || currentOwner == null || currentOwner.isBlank())
            throw new ProtectedInputException();
        try {
            return selected.protect(
                    StandardContributionPoints.INPUT_PROTECTION,
                    new TextProtection.Scope(currentOwner, context.sessionId(), context.agentId()),
                    prompt);
        } catch (RuntimeException failure) {
            throw new ProtectedInputException();
        }
    }
}
