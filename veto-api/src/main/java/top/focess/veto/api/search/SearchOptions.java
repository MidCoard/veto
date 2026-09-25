package top.focess.veto.api.search;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Filters for a search: optional domain allow/block lists and a result cap. A null list means "no
 * filter on that dimension".
 *
 * @param allowedDomains optional domain allowlist
 * @param blockedDomains optional domain blocklist
 * @param maxResults maximum returned hit count
 */
public record SearchOptions(
        @Nullable List<String> allowedDomains,
        @Nullable List<String> blockedDomains,
        int maxResults) {

    /**
     * Creates search options with no domain filters.
     *
     * @param maxResults maximum returned hit count
     * @return unfiltered options with the requested cap
     */
    public static SearchOptions of(int maxResults) {
        return new SearchOptions(null, null, maxResults);
    }
}
