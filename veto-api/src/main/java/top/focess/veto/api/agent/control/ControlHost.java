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
    record Tool(
            @NonNull ToolDefinition definition,
            ControlSubmission.@Nullable Kind control,
            @Nullable String pluginId,
            @Nullable String localId) {}

    @NonNull List<Tool> tools();

    /** Static schema preflight only; deferred JSON paths grant no execution authority. */
    void validateInputs(
            @NonNull String tool, @NonNull JsonNode inputs, @NonNull Set<String> deferredPaths);

    @NonNull SourceEvidence evidence();

    void execute(@NonNull PluginWork work);

    void finish(@NonNull String message, SourceEvidence.@Nullable Receipt receipt);
}
