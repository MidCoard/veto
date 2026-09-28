package top.focess.veto.api.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.RemoteTool;
import top.focess.veto.api.plugin.contribution.ContributionId;

/** Stock schema-authored remote tool whose qualified identity comes from the catalog. */
public final class ToolContribution extends RemoteTool {
    private final @NonNull String description;
    private final JsonValue.@NonNull ObjectValue inputSchema;
    private final JsonValue.@NonNull ObjectValue outputSchema;
    private final @NonNull Effect effect;
    private final @NonNull Set<@NonNull ContributionId> categories;
    private final @NonNull ToolHandler handler;

    /**
     * Creates an immutable portable tool descriptor with its execution handler.
     *
     * @param description model-visible tool description
     * @param inputSchema bounded argument schema
     * @param outputSchema bounded result schema
     * @param effect declared external effect
     * @param categories display categories; these grant no authority
     * @param handler admitted invocation handler
     * @throws IllegalArgumentException when the description is invalid
     */
    public ToolContribution(
            @NonNull String description,
            JsonValue.@NonNull ObjectValue inputSchema,
            JsonValue.@NonNull ObjectValue outputSchema,
            @NonNull Effect effect,
            @NonNull Set<@NonNull ContributionId> categories,
            @NonNull ToolHandler handler) {
        if (description.isBlank() || description.length() > 4096)
            throw new IllegalArgumentException("Invalid tool descriptor");
        this.description = description;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.effect = effect;
        this.categories = Set.copyOf(categories);
        this.handler = handler;
    }

    @Override
    public @NonNull String description() {
        return description;
    }

    @Override
    public JsonValue.@NonNull ObjectValue inputSchema() {
        return inputSchema;
    }

    @Override
    public JsonValue.@NonNull ObjectValue outputSchema() {
        return outputSchema;
    }

    @Override
    public @NonNull Effect effect() {
        return effect;
    }

    @Override
    public @NonNull Set<@NonNull ContributionId> categories() {
        return categories;
    }

    /**
     * Returns handler used after host validation and admission.
     *
     * @return handler used after host validation and admission
     */
    public @NonNull ToolHandler handler() {
        return handler;
    }

    @Override
    public @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
            throws PluginFailure {
        return handler.invoke(arguments, cancellation);
    }
}
