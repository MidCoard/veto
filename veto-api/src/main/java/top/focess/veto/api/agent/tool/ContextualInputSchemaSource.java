package top.focess.veto.api.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolDefinition;

/** A tool-owned schema specialized against the currently advertised capabilities. */
public interface ContextualInputSchemaSource extends InputSchemaSource {
    /**
     * Immutable catalogue used while specializing a schema.
     *
     * @param tools all currently advertised definitions
     * @param localTools names executable in the current local tool set
     * @param submissions control-submission kinds keyed by tool name
     */
    record Context(
            @NonNull List<ToolDefinition> tools,
            @NonNull Set<String> localTools,
            @NonNull Map<String, ControlSubmission.Kind> submissions) {
        /** Creates immutable copies of every catalogue collection. */
        public Context {
            tools = List.copyOf(tools);
            localTools = Set.copyOf(localTools);
            submissions = Map.copyOf(submissions);
        }
    }

    /**
     * Builds a schema specialized to an immutable catalogue snapshot.
     *
     * @param context currently advertised tool catalogue
     * @return model-visible input schema for that catalogue
     */
    @NonNull JsonNode schema(@NonNull Context context);
}
