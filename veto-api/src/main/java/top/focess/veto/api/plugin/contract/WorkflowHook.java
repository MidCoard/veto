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
    /**
     * Current workflow identity and cancellation signal.
     *
     * @param owner authenticated owner, or {@code null} when unavailable for the phase
     * @param sessionId current session
     * @param agentId current agent
     * @param cancellation cooperative cancellation signal
     */
    record Context(
            @Nullable String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation) {}

    /**
     * Selected model endpoint.
     *
     * @param provider selected provider ID
     * @param model selected model ID
     */
    record ModelCall(@NonNull String provider, @NonNull String model) {}

    /**
     * Text portion of a model response.
     *
     * @param message model text, or {@code null} when the response contains no text
     */
    record ModelOutput(@Nullable String message) {}

    /**
     * Immutable tool invocation observation.
     *
     * @param name registered tool name
     * @param callId unique call ID
     * @param arguments validated immutable arguments
     */
    record Invocation(
            @NonNull String name,
            @NonNull String callId,
            JsonValue.@NonNull ObjectValue arguments) {}

    /**
     * Actual tool output observed by hooks.
     *
     * @param content observation text
     * @param format result format
     * @param success actual execution success flag
     */
    record Output(@NonNull String content, @NonNull ToolResultFormat format, boolean success) {}

    /** A hook can tighten a decision, but cannot override another rejection or host policy. */
    enum Decision {
        /** Allow evaluation to continue without requesting extra approval. */
        CONTINUE,
        /** Require host approval unless another hook or host policy rejects. */
        REQUIRE_APPROVAL,
        /** Reject the call. */
        REJECT
    }

    /**
     * Transforms user input before model processing.
     *
     * @param context current workflow context
     * @param text protected input text
     * @return transformed input text
     * @throws PluginFailure when the hook rejects or cannot process the input
     */
    default @NonNull String beforeInput(@NonNull Context context, @NonNull String text)
            throws PluginFailure {
        return text;
    }

    /**
     * Observes the selected model before invocation.
     *
     * @param context current workflow context
     * @param call selected model endpoint
     * @throws PluginFailure when the hook rejects the model invocation
     */
    default void beforeModel(@NonNull Context context, @NonNull ModelCall call)
            throws PluginFailure {}

    /**
     * Transforms model text; native calls and provider-owned signed state are preserved.
     *
     * @param context current workflow context
     * @param call selected model endpoint
     * @param output model output to transform
     * @return transformed model output
     * @throws PluginFailure when the hook rejects or cannot process the output
     */
    default @NonNull ModelOutput afterModel(
            @NonNull Context context, @NonNull ModelCall call, @NonNull ModelOutput output)
            throws PluginFailure {
        return output;
    }

    /**
     * Evaluates a validated tool call before host authorization and execution.
     *
     * @param context current workflow context
     * @param call immutable tool invocation
     * @return this hook's approval decision
     * @throws PluginFailure when policy evaluation fails
     */
    default @NonNull Decision beforeTool(@NonNull Context context, @NonNull Invocation call)
            throws PluginFailure {
        return Decision.CONTINUE;
    }

    /**
     * Transforms the observation body while preserving actual execution status and format.
     *
     * @param context current workflow context
     * @param call immutable tool invocation
     * @param output actual tool output
     * @return transformed observation body
     * @throws PluginFailure when the hook rejects or cannot process the output
     */
    default @NonNull String afterTool(
            @NonNull Context context, @NonNull Invocation call, @NonNull Output output)
            throws PluginFailure {
        return output.content();
    }

    /**
     * Transforms ordinary observation text before publication.
     *
     * @param context current workflow context
     * @param text protected observation text
     * @return transformed observation text
     * @throws PluginFailure when the hook rejects or cannot process the observation
     */
    default @NonNull String beforeObservation(@NonNull Context context, @NonNull String text)
            throws PluginFailure {
        return text;
    }
}
