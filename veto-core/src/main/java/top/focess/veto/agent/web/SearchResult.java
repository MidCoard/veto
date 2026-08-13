package top.focess.veto.agent.web;

import org.jspecify.annotations.NonNull;

/**
 * One web-search hit: the page title, its URL, and a short snippet. Rendered into the {@code
 * web_search} observation.
 */
public record SearchResult(@NonNull String title, @NonNull String url, @NonNull String snippet) {}
