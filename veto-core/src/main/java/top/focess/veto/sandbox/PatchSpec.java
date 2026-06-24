package top.focess.veto.sandbox;

/**
 * A contiguous replacement spec for {@code replace_file_content}. Transcribed from {@code
 * mcp_tool_foundation.md} §10.4.
 *
 * @param startLine 1-indexed starting line (inclusive)
 * @param endLine 1-indexed ending line (inclusive)
 * @param targetContent the exact text to replace
 * @param replacementContent the replacement text
 */
public record PatchSpec(
        int startLine, int endLine, String targetContent, String replacementContent) {}
