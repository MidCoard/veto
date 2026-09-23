package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/**
 * Trusted, session-scoped workflow callbacks. Contributions run in catalog order on the workflow
 * thread. Throw PluginFailure to stop the operation; cancellation is cooperative. Tool arguments
 * are immutable and callbacks never acquire authority by observing a call.
 */
public interface WorkflowHook {
    record Context(
            @Nullable String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation) {}

    record ModelCall(@NonNull String provider, @NonNull String model) {}

    record ModelOutput(@Nullable String message) {}

    record Invocation(
            @NonNull String name,
            @NonNull String callId,
            JsonValue.@NonNull ObjectValue arguments) {}

    record Output(@NonNull String content, @NonNull ToolResultFormat format, boolean success) {}

    /** A hook can tighten a decision, but cannot override another rejection or host policy. */
    enum Decision {
        CONTINUE,
        REQUIRE_APPROVAL,
        REJECT
    }

    default @NonNull String beforeInput(@NonNull Context context, @NonNull String text)
            throws PluginFailure {
        return text;
    }

    default void beforeModel(@NonNull Context context, @NonNull ModelCall call)
            throws PluginFailure {}

    /** Transforms model text; native calls and provider-owned signed state are preserved. */
    default @NonNull ModelOutput afterModel(
            @NonNull Context context, @NonNull ModelCall call, @NonNull ModelOutput output)
            throws PluginFailure {
        return output;
    }

    default @NonNull Decision beforeTool(@NonNull Context context, @NonNull Invocation call)
            throws PluginFailure {
        return Decision.CONTINUE;
    }

    /** Transforms the observation body while preserving the actual execution status and format. */
    default @NonNull String afterTool(
            @NonNull Context context, @NonNull Invocation call, @NonNull Output output)
            throws PluginFailure {
        return output.content();
    }

    default @NonNull String beforeObservation(@NonNull Context context, @NonNull String text)
            throws PluginFailure {
        return text;
    }
}
