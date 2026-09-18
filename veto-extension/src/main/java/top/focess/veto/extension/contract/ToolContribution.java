package top.focess.veto.extension.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.ExtensionId;

/** Tool extension payload; its qualified identity comes from the shared catalog. */
public record ToolContribution(
        @NonNull String description,
        JsonValue.@NonNull ObjectValue inputSchema,
        JsonValue.@NonNull ObjectValue outputSchema,
        @NonNull Effect effect,
        @NonNull Set<@NonNull ExtensionId> categories,
        @NonNull ToolHandler handler) {
    /** Only pure computation is exposed until effectful capability bridges are implemented. */
    public enum Effect {
        COMPUTATION
    }

    public ToolContribution {
        categories = Set.copyOf(categories);
        if (description.isBlank() || description.length() > 4096) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
    }
}
