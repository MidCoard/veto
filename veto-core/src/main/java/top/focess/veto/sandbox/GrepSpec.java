package top.focess.veto.sandbox;

import java.util.List;

/**
 * A search spec for {@code grep_search}. Transcribed from {@code mcp_tool_foundation.md} §10.5.
 *
 * @param query the exact pattern to match
 * @param caseInsensitive whether to match case-insensitively
 * @param includes glob filters for which files to include
 */
public record GrepSpec(String query, boolean caseInsensitive, List<String> includes) {}
