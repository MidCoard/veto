package top.focess.veto.agent.mcp;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Reflects {@link ToolDoc} off a tool's args class into the {@link ToolDefinition#examples()} and
 * {@link ToolDefinition#longDescription()} accessors. Centralized so {@link AgentToolDefinition}
 * and {@link NativeToolDefinition} share one read path; a missing annotation yields an empty list /
 * empty string (the tool renders without long-form doc or examples).
 *
 * <p>The annotation is resolved by {@link #toolDocOf(Class)}: read directly off the args class, and
 * if absent there, off the args class's enclosing tool class. A tool may therefore declare
 * {@code @ToolDoc} on either its args record (the convention for {@code NativeTool}s like {@code
 * ListDirTool}) or its enclosing bean class (the convention for the nested agent tools in {@code
 * MemoryTools}); both placements render.
 */
public final class ToolDocs {

    private ToolDocs() {}

    /**
     * Normalizes javac class-literal nullness for Checker Framework. A class literal cannot be
     * null, but a nullable-by-default package otherwise gives the expression a nullable outer
     * {@link Class} type.
     */
    public static <T extends @NonNull Object> @NonNull Class<T> nonNullClass(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Class token is required");
        }
        return type;
    }

    /**
     * Resolves the {@link ToolDoc} for a tool from its args class. The annotation is read directly
     * off the args class; if absent there, off the args class's enclosing tool class - so a tool
     * may declare {@code @ToolDoc} on either its args record or its enclosing bean class. Returns
     * null when neither carries the annotation (or when {@code argsClass} is null).
     */
    static ToolDoc toolDocOf(Class<?> argsClass) {
        if (argsClass == null) {
            return null;
        }
        ToolDoc doc = argsClass.getAnnotation(nonNullClass(ToolDoc.class));
        if (doc != null) {
            return doc;
        }
        Class<?> enclosing = argsClass.getEnclosingClass();
        return enclosing != null ? enclosing.getAnnotation(nonNullClass(ToolDoc.class)) : null;
    }

    public static @NonNull List<String> examplesOf(Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        String[] examples = doc == null ? null : doc.examples();
        return examples == null ? List.of() : List.of(examples);
    }

    /**
     * Returns the {@link ToolDoc#returnExamples()} for the given args class, or an empty list when
     * the class is null or has no {@code @ToolDoc}.
     */
    public static @NonNull List<String> returnExamplesOf(Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        String[] examples = doc == null ? null : doc.returnExamples();
        return examples == null ? List.of() : List.of(examples);
    }

    /**
     * Returns the long-form {@link ToolDoc#usage()} for the given args class, or an empty string
     * when the class is null or has no {@code @ToolDoc}.
     */
    public static @NonNull String descriptionOf(Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc == null ? "" : doc.usage();
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
        if (text.isEmpty()) {
            return "";
        }
        int dot = text.indexOf('.');
        return (dot >= 0) ? text.substring(0, dot + 1) : text;
    }
}
