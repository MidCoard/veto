package top.focess.veto.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolPresentation;

/** Generic optional presentation facts; availability can restrict but never grant tools. */
public final class ToolPresentations {
    private ToolPresentations() {}

    /**
     * Computes the tool's presentation state against a read-only view of the session workspace
     * roots, with host effects disabled. Tools without a presentation are always available.
     */
    public static ToolPresentation.@NonNull State inspect(
            @NonNull ToolDefinition definition, @NonNull List<@NonNull Path> roots) {
        if (!(definition instanceof LocalToolDefinition local))
            return new ToolPresentation.State(true, Map.of());
        var callback = local.presentation();
        if (callback == null) return new ToolPresentation.State(true, Map.of());
        // Caller supplies only its already-authorized persona tools and session workspace.
        var active = new AtomicBoolean(true);
        try {
            return ToolCallContextHolder.withoutEffects(
                    () ->
                            callback.describe(
                                    new ReadOnlyCatalogueTree(
                                            roots,
                                            () -> {
                                                if (!active.get())
                                                    throw new SecurityException(
                                                            "Presentation grant expired");
                                            })));
        } finally {
            active.set(false);
        }
    }

    /**
     * Enforces that the tool is available in this workspace.
     *
     * @throws SecurityException if its presentation reports the tool unavailable
     */
    public static void requireAvailable(
            @NonNull ToolDefinition definition, @NonNull List<@NonNull Path> roots) {
        if (!inspect(definition, roots).available())
            throw new SecurityException("Tool is unavailable in this workspace");
    }
}
