package top.focess.veto.api.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contribution.ContributionId;

/**
 * Stock schema-authored {@link Tool}; its qualified identity comes from the catalog.
 *
 * @param description human-readable tool description
 * @param inputSchema JSON schema for arguments
 * @param outputSchema JSON schema for successful results
 * @param effect declared effect class used as authorization input
 * @param categories semantic category IDs, copied on construction
 * @param handler invocation implementation
 */
public record ToolContribution(
        @NonNull String description,
        JsonValue.@NonNull ObjectValue inputSchema,
        JsonValue.@NonNull ObjectValue outputSchema,
        @NonNull Effect effect,
        @NonNull Set<@NonNull ContributionId> categories,
        @NonNull ToolHandler handler)
        implements Tool {
    /** Defensively copies categories and validates the description. */
    public ToolContribution {
        categories = Set.copyOf(categories);
        if (description.isBlank() || description.length() > 4096) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
    }

    @Override
    public @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
            throws PluginFailure {
        return handler.invoke(arguments, cancellation);
    }
}
