package top.focess.veto.agent.tool;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Reflects {@link ToolDoc} off a tool's args class into the {@link ToolDefinition#examples()} and
 * {@link ToolDefinition#documentation()} accessors. Centralized so {@link AgentToolDefinition} and
 * {@link NativeToolDefinition} share one read path; a missing annotation yields an empty list /
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

    static @NonNull String descriptionOf(@NonNull Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc != null && !doc.description().isEmpty()
                ? doc.description()
                : firstSentenceOf(doc == null ? "" : doc.behavior());
    }

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
     * null when neither carries the annotation.
     */
    static ToolDoc toolDocOf(@NonNull Class<?> argsClass) {
        ToolDoc doc = argsClass.getAnnotation(nonNullClass(ToolDoc.class));
        if (doc != null) {
            return doc;
        }
        Class<?> enclosing = argsClass.getEnclosingClass();
        return enclosing != null ? enclosing.getAnnotation(nonNullClass(ToolDoc.class)) : null;
    }

    public static @NonNull List<String> examplesOf(@NonNull Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc == null ? List.of() : List.of(doc.examples());
    }

    /**
     * Returns the {@link ToolDoc#returnExamples()} for the given args class, or an empty list when
     * the class has no {@code @ToolDoc}.
     */
    public static @NonNull List<String> returnExamplesOf(@NonNull Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc == null ? List.of() : List.of(doc.returnExamples());
    }

    /** Returns the explicitly declared wire result formats for a documented Veto tool. */
    public static @NonNull List<@NonNull ToolResultFormat> resultFormatsOf(
            @NonNull Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc == null ? List.of() : List.of(doc.resultFormats());
    }

    /** Returns the typed documentation sections for a tool. */
    public static @NonNull ToolDocumentation documentationOf(@NonNull Class<?> argsClass) {
        ToolDoc doc = toolDocOf(argsClass);
        return doc == null
                ? ToolDocumentation.empty()
                : new ToolDocumentation(
                        doc.behavior(),
                        doc.whenToUse(),
                        doc.whenNotToUse(),
                        doc.resultContract(),
                        doc.errorsAndEdgeCases(),
                        doc.security());
    }

    /** Extracts the first sentence (up to and including the first period) from text. */
    public static @NonNull String firstSentenceOf(@NonNull String text) {
        if (text.isEmpty()) {
            return "";
        }
        int dot = text.indexOf('.');
        return (dot >= 0) ? text.substring(0, dot + 1) : text;
    }
}
