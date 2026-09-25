package top.focess.veto.api.agent.workflow;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;

/**
 * Plugin-owned execution submitted to the agent workflow. The callback runs under host lifecycle
 * admission; every operation requested through {@link Runtime} remains budgeted and authorized.
 */
@FunctionalInterface
public interface PluginWork {
    /** Opaque plugin-owned citation source carried back through generated messages. */
    interface Source {}

    /**
     * Input to one host-mediated model generation.
     *
     * @param prompt complete prompt text
     * @param inputs structured prompt inputs
     * @param modelTier optional host model-tier selector
     * @param temperature optional sampling override
     * @param thought optional request for provider reasoning output
     * @param allowedTools immutable set of tool names available to this generation
     */
    record ModelInput(
            @NonNull String prompt,
            @NonNull Map<String, Object> inputs,
            String modelTier,
            Double temperature,
            Boolean thought,
            @NonNull Set<String> allowedTools) {
        /** Defensively copies the set of tools allowed for this generation. */
        public ModelInput {
            allowedTools = Set.copyOf(allowedTools);
        }
    }

    /**
     * Result of one host-mediated generation.
     *
     * @param response decoded model response
     * @param citations optional plugin-owned citation source
     * @param modelCallId optional host correlation identifier
     */
    record Generated(@NonNull VetoResponse response, Source citations, String modelCallId) {}

    /** Call-scoped operations exposed while a plugin work item is running. */
    interface Runtime {
        /**
         * Checks whether workflow operations may continue.
         *
         * @return whether the owning agent request is still running
         */
        boolean running();

        /** Performs the host's cancellation and budget checks before another workflow step. */
        void beforeStep();

        /**
         * Identifies the model call that originated this workflow.
         *
         * @return the originating call identifier, or {@code null} when none exists
         */
        String sourceCallId();

        /**
         * Executes an authorized tool call with workflow correlation data.
         *
         * @param call requested tool call
         * @param context plugin-owned explanatory context
         * @return structured tool result
         */
        @NonNull ToolResult tool(@NonNull ToolCall call, @NonNull ActionContext context);

        /**
         * Performs a host-mediated model call under the supplied response contract.
         *
         * @param input model prompt and options
         * @param contract required response shape
         * @return decoded response and host correlation data
         */
        @NonNull Generated generate(@NonNull ModelInput input, @NonNull ResponseContract contract);

        /**
         * Records a workflow message and its optional citation/call correlation.
         *
         * @param text message text
         * @param citations optional plugin-owned citation source
         * @param callId optional model call identifier
         * @param forwarded whether the message was forwarded from another participant
         */
        void message(@NonNull String text, Source citations, String callId, boolean forwarded);

        /**
         * Records a named, model-visible observation.
         *
         * @param topic observation topic
         * @param text observation text
         */
        void observation(@NonNull String topic, @NonNull String text);

        /**
         * Renders a host prompt template with structured data.
         *
         * @param source prompt-template resource identifier
         * @param data structured template data
         * @return rendered prompt text
         */
        @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data);
    }

    /**
     * Runs this work item synchronously using the current call-scoped runtime.
     *
     * @param runtime authorized runtime for the current agent request
     */
    void run(@NonNull Runtime runtime);
}
