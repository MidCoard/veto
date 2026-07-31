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
     * Returns the {@link ToolDoc#returnExamples()} for the given args class, or an empty list when
     * the class is null or has no {@code @ToolDoc}.
     */
    public static @NonNull List<String> returnExamplesOf(@Nullable Class<?> argsClass) {
        if (argsClass == null) {
            return List.of();
        }
        ToolDoc doc = argsClass.getAnnotation(ToolDoc.class);
        if (doc == null) {
            return List.of();
        }
        return List.of(doc.returnExamples());
    }

    /**
     * Returns the long-form {@link ToolDoc#usage()} for the given args class, or an empty string
     * when the class is null or has no {@code @ToolDoc}.
     */
    public static @NonNull String descriptionOf(@Nullable Class<?> argsClass) {
        if (argsClass == null) {
            return "";
        }
        ToolDoc doc = argsClass.getAnnotation(ToolDoc.class);
        if (doc == null) {
            return "";
        }
        return doc.usage();
    }

    /**
     * Derives the tool name from an args class: strips the {@code Args} suffix and converts
     * CamelCase to snake_case. E.g. {@code ThinkArgs} -> {@code "think"}, {@code LoadSkillArgs} ->
     * {@code "load_skill"}.
     */
    public static @NonNull String toolNameOf(@NonNull Class<?> argsClass) {
        String simple = argsClass.getSimpleName();
        if (simple.endsWith("Args")) {
            simple = simple.substring(0, simple.length() - 4);
        }
        return simple.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Fallback description when {@link ToolDoc#description()} is empty: extracts the first sentence
     * (up to and including the first period) from the usage text.
     */
    public static @NonNull String firstSentenceOf(@NonNull String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int dot = text.indexOf('.');
        return (dot >= 0) ? text.substring(0, dot + 1) : text;
    }
}
