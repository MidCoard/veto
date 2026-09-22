package top.focess.veto.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.ContributionId;

/** Stock schema-authored {@link Tool.SchemaTool}; its qualified identity comes from the catalog. */
public record ToolContribution(
        @NonNull String description,
        JsonValue.@NonNull ObjectValue inputSchema,
        JsonValue.@NonNull ObjectValue outputSchema,
        @NonNull Effect effect,
        @NonNull Set<@NonNull ContributionId> categories,
        @NonNull ToolHandler handler)
        implements Tool.SchemaTool {
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
