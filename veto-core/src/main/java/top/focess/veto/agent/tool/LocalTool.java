package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import top.focess.veto.util.Nullness;

/** Shared typed execution contract for in-process tools. Security remains on each tool kind. */
public interface LocalTool<T> {
    @NonNull String getName();

    @NonNull Class<T> getArgsClass();

    @NonNull String execute(@NonNull T args) throws Exception;

    default @NonNull String executeFromJson(
            @NonNull JsonNode jsonArgs, @NonNull ObjectMapper mapper) throws Exception {
        T typedArgs = mapper.treeToValue(jsonArgs, getArgsClass());
        return execute(Nullness.requireNonNull(typedArgs, "Tool arguments deserialized to null"));
    }
}
