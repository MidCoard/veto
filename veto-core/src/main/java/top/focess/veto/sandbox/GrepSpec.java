package top.focess.veto.sandbox;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A search spec for {@code grep_search}..
 *
 * @param query the exact pattern to match
 * @param caseInsensitive whether to match case-insensitively
 * @param includes glob filters for which files to include
 */
public record GrepSpec(
        @NonNull String query, boolean caseInsensitive, @NonNull List<String> includes) {}
