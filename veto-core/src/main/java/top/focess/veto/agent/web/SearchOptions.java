package top.focess.veto.agent.web;

import java.util.List;

/**
 * Filters for a search: optional domain allow/block lists and a result cap. A null list means "no
 * filter on that dimension".
 */
public record SearchOptions(
        List<String> allowedDomains, List<String> blockedDomains, int maxResults) {

    /** No domain filters, default cap. */
    public static SearchOptions of(int maxResults) {
        return new SearchOptions(null, null, maxResults);
    }
}
