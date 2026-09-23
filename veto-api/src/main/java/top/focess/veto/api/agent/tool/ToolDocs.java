package top.focess.veto.api.agent.tool;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Shared reflection helpers for record-authored tool documentation. */
public final class ToolDocs {

    private ToolDocs() {}

    public static @NonNull String descriptionOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
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

    /** Reads tool-class documentation without inferring an owner from the argument record. */
    public static ToolDoc toolDocOf(@NonNull Class<?> toolClass) {
        return toolClass.getAnnotation(nonNullClass(ToolDoc.class));
    }

    public static @NonNull List<String> examplesOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.examples());
    }

    /**
     * Returns the {@link ToolDoc#returnExamples()} for the given tool class, or an empty list when
     * the class has no {@code @ToolDoc}.
     */
    public static @NonNull List<String> returnExamplesOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.returnExamples());
    }

    /** Returns the explicitly declared wire result formats for a documented Veto tool. */
    public static @NonNull List<@NonNull ToolResultFormat> resultFormatsOf(
            @NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.resultFormats());
    }

    /** Returns the typed documentation sections for a tool. */
    public static @NonNull ToolDocumentation documentationOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
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
