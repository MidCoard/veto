package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.plugin.runtime.PluginJson;

/** Invokes selected plugin hooks and protects inputs at the host boundary. */
final class AgentPluginHooks {
    private final @NonNull AgentRuntimeState runtime;

    AgentPluginHooks(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    WorkflowHook.@NonNull Context workflowContext() {
        return new WorkflowHook.Context(
                runtime.owner,
                runtime.sessionId.toString(),
                runtime.agentId,
                () -> Thread.currentThread().isInterrupted());
    }

    <T extends @NonNull Object> @NonNull T workflow(
            @NonNull T initial, SessionPlugins.@NonNull WorkflowOperation<T> operation) {
        var selected = runtime.sessionPlugins;
        if (selected == null
                || !selected.has(runtime.sessionId.toString(), StandardContributionPoints.WORKFLOW))
            return initial;
        runtime.lifecycle().checkTaskCancellation();
        T result = selected.workflow(workflowContext(), initial, operation);
        runtime.lifecycle().checkTaskCancellation();
        return result;
    }

    WorkflowHook.@NonNull Invocation hookInvocation(@NonNull ToolCall call) {
        return new WorkflowHook.Invocation(
                call.toolName(),
                call.callId(),
                PluginJson.object(runtime.objectMapper.valueToTree(call.args())));
    }

    WorkflowHook.@NonNull Decision beforeToolHooks(@NonNull ToolCall call) {
        return runtime.hooks()
                .workflow(
                        WorkflowHook.Decision.CONTINUE,
                        (hook, previous) -> {
                            if (previous == WorkflowHook.Decision.REJECT) return previous;
                            var next = hook.beforeTool(workflowContext(), hookInvocation(call));
                            return next.ordinal() > previous.ordinal() ? next : previous;
                        });
    }

    @NonNull VetoResponse callModelWithHooks(@NonNull VetoRequest request) {
        var model = new WorkflowHook.ModelCall(request.providerType().name(), request.modelName());
        runtime.hooks()
                .workflow(
                        model,
                        (hook, current) -> {
                            hook.beforeModel(workflowContext(), current);
                            return current;
                        });
        VetoResponse response = runtime.caller.call(request);
        runtime.lifecycle().checkTaskCancellation();
        var transformed =
                runtime.hooks()
                        .workflow(
                                new WorkflowHook.ModelOutput(response.message()),
                                (hook, output) ->
                                        hook.afterModel(workflowContext(), model, output));
        return new VetoResponse(
                response.thought(), response.calls(), transformed.message(), response.citations());
    }

    @NonNull String captureUserPrompt(@NonNull String prompt) {
        synchronized (runtime) {
            var selected = runtime.sessionPlugins;
            if (selected == null) return prompt;
            String currentOwner = runtime.owner;
            if (!runtime.sessionAlive || currentOwner == null || currentOwner.isBlank())
                throw new ProtectedInputException();
            try {
                return selected.protect(
                        StandardContributionPoints.INPUT_PROTECTION,
                        new TextProtection.Scope(
                                currentOwner, runtime.sessionId.toString(), runtime.agentId),
                        prompt);
            } catch (RuntimeException failure) {
                throw new ProtectedInputException();
            }
        }
    }
}
