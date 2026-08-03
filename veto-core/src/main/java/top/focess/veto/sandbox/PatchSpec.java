package top.focess.veto.sandbox;

import org.jspecify.annotations.NonNull;

/**
 * A contiguous replacement spec for {@code replace_file_content}..
 *
 * @param startLine 1-indexed starting line (inclusive)
 * @param endLine 1-indexed ending line (inclusive)
 * @param targetContent the exact text to replace
 * @param replacementContent the replacement text
 */
public record PatchSpec(
        int startLine,
        int endLine,
        @NonNull String targetContent,
        @NonNull String replacementContent) {}
