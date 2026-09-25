package top.focess.veto.api.search;

import org.jspecify.annotations.NonNull;

/**
 * One web-search hit: the page title, its URL, and a short snippet. Rendered into the {@code
 * web_search} observation.
 *
 * @param title page title
 * @param url page URL
 * @param snippet short result excerpt
 */
public record SearchResult(@NonNull String title, @NonNull String url, @NonNull String snippet) {}
