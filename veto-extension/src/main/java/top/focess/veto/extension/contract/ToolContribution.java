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
    /** Declared effects; the host remains responsible for authorization. */
    public enum Effect {
        COMPUTATION,
        CREDENTIAL_IMPORT,
        /** Trusted external code whose effects require host scrutiny on every call. */
        EXTERNAL_UNKNOWN
    }

    public ToolContribution {
        categories = Set.copyOf(categories);
        if (description.isBlank() || description.length() > 4096) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
    }
}
