package top.focess.veto.api.agent.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.ToolDefinition;

/**
 * Current-call control transfer. Every subsequent operation retains host budgets and authorization.
 */
public interface ControlHost {
    /**
     * One tool visible to the current control call.
     *
     * @param definition model-visible tool definition
     * @param control exclusive control kind, or {@code null} for an ordinary tool
     * @param pluginId owning plugin identifier, when plugin-owned
     * @param localId plugin-local tool identifier, when plugin-owned
     */
    record Tool(
            @NonNull ToolDefinition definition,
            ControlSubmission.@Nullable Kind control,
            @Nullable String pluginId,
            @Nullable String localId) {}

    /**
     * Lists the tools available to the current control call.
     *
     * @return the immutable catalogue of tools visible to the current call
     */
    @NonNull List<Tool> tools();

    /**
     * Performs static schema preflight; deferred JSON paths grant no execution authority.
     *
     * @param tool visible tool name
     * @param inputs proposed input document
     * @param deferredPaths JSON paths whose values will be supplied later
     */
    void validateInputs(
            @NonNull String tool, @NonNull JsonNode inputs, @NonNull Set<String> deferredPaths);

    /**
     * Opens evidence inspection for the current model input.
     *
     * @return evidence inspection bound to the exact current model input
     */
    @NonNull SourceEvidence evidence();

    /**
     * Transfers control to plugin-owned work.
     *
     * @param work plugin-owned work that receives the current authorized runtime
     */
    void execute(@NonNull PluginWork work);

    /**
     * Completes the current call with user-facing text and optional validated evidence.
     *
     * @param message final response text
     * @param receipt host-issued evidence receipt, or {@code null}
     */
    void finish(@NonNull String message, SourceEvidence.@Nullable Receipt receipt);
}
