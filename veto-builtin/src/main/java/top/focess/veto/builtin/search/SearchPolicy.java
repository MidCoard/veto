package top.focess.veto.builtin.search;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;

/** One result policy shared by local providers, service callbacks and the public search tool. */
public final class SearchPolicy {
    private SearchPolicy() {}

    /** Applies domain filters before the requested cap; nonpositive caps retain the default ten. */
    public static @NonNull List<@NonNull SearchResult> apply(
            @NonNull List<@NonNull SearchResult> results, @NonNull SearchOptions options) {
        var allowed = options.allowedDomains();
        var blocked = options.blockedDomains();
        boolean filtered =
                (allowed != null && !allowed.isEmpty()) || (blocked != null && !blocked.isEmpty());
        return results.stream()
                .filter(
                        result -> {
                            if (!filtered) return true;
                            String host = host(result.url());
                            return host != null
                                    && !matches(host, blocked)
                                    && (allowed == null
                                            || allowed.isEmpty()
                                            || matches(host, allowed));
                        })
                .limit(options.maxResults() > 0 ? options.maxResults() : 10)
                .toList();
    }

    private static boolean matches(@NonNull String host, List<@NonNull String> domains) {
        if (domains == null) return false;
        return domains.stream()
                .map(SearchPolicy::normalize)
                .anyMatch(
                        domain ->
                                !domain.isEmpty()
                                        && (host.equals(domain) || host.endsWith("." + domain)));
    }

    private static @NonNull String normalize(@NonNull String domain) {
        String value = domain.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private static String host(@NonNull String url) {
        try {
            String value = URI.create(url).getHost();
            return value == null ? null : normalize(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
