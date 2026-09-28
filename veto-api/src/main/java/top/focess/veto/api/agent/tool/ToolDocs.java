package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;


import java.util.List;

/** Shared reflection helpers for record-authored tool documentation. */
public final class ToolDocs {

    private ToolDocs() {}

    /**
     * Reads a tool's short description, falling back to the first behavior sentence.
     *
     * @param toolClass concrete tool implementation class
     * @return the short description, or an empty string when undocumented
     */
    public static @NonNull String descriptionOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc != null && !doc.description().isEmpty()
                ? doc.description()
                : firstSentenceOf(doc == null ? "" : doc.behavior());
    }

    /**
     * Reads tool-class documentation without inferring an owner from the argument record.
     *
     * @param toolClass concrete tool implementation class
     * @return its annotation, or {@code null} when undocumented
     */
    public static ToolDoc toolDocOf(@NonNull Class<?> toolClass) {
        return toolClass.getAnnotation(ToolDoc.class);
    }

    /**
     * Reads the concrete argument examples declared by a tool.
     *
     * @param toolClass concrete tool implementation class
     * @return immutable argument examples, or an empty list when undocumented
     */
    public static @NonNull List<String> examplesOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.examples());
    }

    /**
     * Returns the {@link ToolDoc#returnExamples()} for the given tool class, or an empty list when
     * the class has no {@code @ToolDoc}.
     *
     * @param toolClass concrete tool implementation class
     * @return immutable representative results
     */
    public static @NonNull List<String> returnExamplesOf(@NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.returnExamples());
    }

    /**
     * Reads the successful-result encodings declared by a tool.
     *
     * @param toolClass concrete tool implementation class
     * @return explicitly declared successful-result formats, or an empty list
     */
    public static @NonNull List<@NonNull ToolResultFormat> resultFormatsOf(
            @NonNull Class<?> toolClass) {
        ToolDoc doc = toolDocOf(toolClass);
        return doc == null ? List.of() : List.of(doc.resultFormats());
    }

    /**
     * Resolves all typed documentation sections for a tool.
     *
     * @param toolClass concrete tool implementation class
     * @return typed documentation sections, or {@link ToolDocumentation#empty()}
     */
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

    /**
     * Extracts the first sentence up to and including its first period.
     *
     * @param text source text
     * @return first sentence, or all text when it has no period
     */
    public static @NonNull String firstSentenceOf(@NonNull String text) {
        if (text.isEmpty()) {
            return "";
        }
        int dot = text.indexOf('.');
        return (dot >= 0) ? text.substring(0, dot + 1) : text;
    }
}
