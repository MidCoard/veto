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
     */
    @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception;

    /** The provider's short name (matches {@code veto.websearch.provider}). */
    @NonNull String name();
}
