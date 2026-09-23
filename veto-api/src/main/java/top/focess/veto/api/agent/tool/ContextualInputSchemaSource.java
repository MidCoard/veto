package top.focess.veto.api.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolDefinition;

/** A tool-owned schema specialized against the currently advertised capabilities. */
public interface ContextualInputSchemaSource extends InputSchemaSource {
    record Context(
            @NonNull List<ToolDefinition> tools,
            @NonNull Set<String> localTools,
            @NonNull Map<String, ResponseSubmission.Kind> submissions) {
        public Context {
            tools = List.copyOf(tools);
            localTools = Set.copyOf(localTools);
            submissions = Map.copyOf(submissions);
        }
    }

    @NonNull JsonNode schema(@NonNull Context context);
}
