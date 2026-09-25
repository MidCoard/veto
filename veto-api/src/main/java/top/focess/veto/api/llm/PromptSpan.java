package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/**
 * Source location of text included in a compiled prompt.
 *
 * @param source source identifier
 * @param line one-based source line
 * @param column one-based source column
 * @param start start offset within the compiled text
 * @param end exclusive end offset within the compiled text
 */
public record PromptSpan(@NonNull String source, int line, int column, int start, int end) {}
