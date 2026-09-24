package top.focess.veto.agent.tool;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolPresentation;

/**
 * An in-process tool defined by a Java class annotated with {@code @ToolSecurity}. It carries both
 * the args class (for deserialization &amp; execution) and per-parameter {@link ParamCategory}
 * hints (for the Gateway). A JAR plugin contributes the same shape: {@link #provenance()} is
 * non-null only for a plugin-sourced tool, which keeps it session-scoped and revision-pinned.
 */
public record NativeToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        boolean requiresSemanticScreening,
        @NonNull Class<?> toolClass,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints,
        @Nullable Provenance provenance,
        @Nullable ToolPresentation presentation)
        implements LocalToolDefinition {
    public NativeToolDefinition {
        paramHints = Map.copyOf(paramHints);
    }

    public NativeToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull ToolCapability capability,
            @NonNull Danger defaultDanger,
            boolean requiresSemanticScreening,
            @NonNull Class<?> toolClass,
            @NonNull Class<?> argsClass,
            @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints,
            @Nullable Provenance provenance) {
        this(
                name,
                description,
                capability,
                defaultDanger,
                requiresSemanticScreening,
                toolClass,
                argsClass,
                paramHints,
                provenance,
                null);
    }

    /** Host-shipped native tool with no plugin provenance. */
    public NativeToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull ToolCapability capability,
            @NonNull Danger defaultDanger,
            boolean requiresSemanticScreening,
            @NonNull Class<?> toolClass,
            @NonNull Class<?> argsClass,
            @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints) {
        this(
                name,
                description,
                capability,
                defaultDanger,
                requiresSemanticScreening,
                toolClass,
                argsClass,
                paramHints,
                null);
    }
}
