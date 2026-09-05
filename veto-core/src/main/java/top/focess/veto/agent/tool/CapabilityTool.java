package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import top.focess.veto.util.Nullness;

/** Common execution and effect contract for every in-process tool. */
public interface CapabilityTool<T> {
    /** The effect boundary required to authorize this tool. */
    @NonNull ToolCapability getCapability();

    @NonNull String getName();

    @NonNull Class<T> getArgsClass();

    @NonNull String execute(@NonNull T args) throws Exception;

    default @NonNull String executeFromJson(
            @NonNull JsonNode jsonArgs, @NonNull ObjectMapper mapper) throws Exception {
        T typedArgs = mapper.treeToValue(jsonArgs, getArgsClass());
        return execute(Nullness.requireNonNull(typedArgs, "Tool arguments deserialized to null"));
    }
}
