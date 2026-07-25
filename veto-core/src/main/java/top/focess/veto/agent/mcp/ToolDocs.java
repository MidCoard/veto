package top.focess.veto.agent.mcp;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Reflects {@link ToolDoc} off a tool's args class into the {@link ToolDefinition#examples()} and
 * {@link ToolDefinition#longDescription()} accessors. Centralized so {@link AgentToolDefinition}
 * and {@link NativeToolDefinition} share one read path; a missing annotation yields an empty list /
 * empty string (the tool renders without long-form doc or examples).
 */
public final class ToolDocs {

    private ToolDocs() {}

    public static @NonNull List<String> examplesOf(@Nullable Class<?> argsClass) {
        if (argsClass == null) {
            return List.of();
        }
        ToolDoc doc = argsClass.getAnnotation(ToolDoc.class);
        if (doc == null) {
            return List.of();
        }
        return List.of(doc.examples());
    }

    /**
     * Returns the long-form {@link ToolDoc#description()} for the given args class, or an empty
     * string when the class is null or has no {@code @ToolDoc}.
     */
    public static @NonNull String descriptionOf(@Nullable Class<?> argsClass) {
        if (argsClass == null) {
            return "";
        }
        ToolDoc doc = argsClass.getAnnotation(ToolDoc.class);
        if (doc == null) {
            return "";
        }
        return doc.description();
    }
}
