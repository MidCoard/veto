package top.focess.veto.api.search;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Web-search backend contributed through the plugin catalog. */
public interface SearchProvider {

    /**
     * Runs a search and returns the results, best-effort. Implementations should return an empty
     * list (not throw) when the backend yields nothing, and throw only on hard transport errors.
     *
     * @param query the search query (>= 2 chars)
     * @param options domain filters + result cap
     * @return search hits, or an empty list when the backend found none
     * @throws Exception for a hard backend or transport failure
     */
    @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception;

    /**
     * Returns the provider's short name (matches {@code veto.websearch.provider}).
     *
     * @return stable short provider name
     */
    @NonNull String name();
}
