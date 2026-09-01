package top.focess.veto.agent.web;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keyless web search via DuckDuckGo's HTML endpoint — the default {@link SearchProvider} so {@code
 * web_search} works out of the box with no API key (mirrors how a hosted assistant shields the user
 * from key management). Best-effort: DuckDuckGo may throttle or reshape the page, in which case the
 * provider returns what it can parse (possibly empty) rather than failing the tool.
 */
@Component
@ConditionalOnProperty(
        name = "veto.websearch.provider",
        havingValue = "duckduckgo",
        matchIfMissing = true)
public class DuckDuckGoSearchProvider implements SearchProvider {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.web.DuckDuckGoSearchProvider");
    private static final @NonNull String ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    // A browser-like UA; the default Java client UA is frequently blocked.
    private static final @NonNull String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0 Safari/537.36";

    private final @NonNull HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    @Override
    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        if (query.isBlank() || query.strip().length() < 2) {
            throw new IllegalArgumentException("web_search query must be at least 2 characters");
        }
        String url = ENDPOINT + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html")
                        .GET()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("DuckDuckGo search returned HTTP {}", response.statusCode());
            throw new IllegalStateException(
                    "DuckDuckGo search failed: HTTP " + response.statusCode());
        }
        List<SearchResult> results = parse(response.body());
        results = applyDomainFilters(results, options);
        int cap = options.maxResults() > 0 ? options.maxResults() : 10;
        return results.size() > cap ? results.subList(0, cap) : results;
    }

    @Override
    public @NonNull String name() {
        return "duckduckgo";
    }

    /** Parses DuckDuckGo's HTML results page into {@link SearchResult}s. */
    private @NonNull List<SearchResult> parse(@NonNull String html) {
        List<SearchResult> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element result : doc.select("div.result, div.web-result")) {
            Element link = result.selectFirst("a.result__a");
            if (link == null) {
                continue;
            }
            String title = link.text().trim();
            String href = unwrap(link.attr("href"));
            if (title.isEmpty() || href.isEmpty()) {
                continue;
            }
            Element snippetEl = result.selectFirst("a.result__snippet, .result__snippet");
            String snippet = snippetEl != null ? snippetEl.text().trim() : "";
            out.add(new SearchResult(title, href, snippet));
        }
        return out;
    }

    /**
     * DuckDuckGo wraps result links in a redirect ({@code //duckduckgo.com/l/?uddg=<encoded>});
     * recover the real destination URL when present, else use the href as-is.
     */
    private @NonNull String unwrap(@NonNull String href) {
        if (href.contains("uddg=")) {
            int start = href.indexOf("uddg=") + "uddg=".length();
            int end = href.indexOf('&', start);
            String encoded = end == -1 ? href.substring(start) : href.substring(start, end);
            try {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return href;
            }
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        return href;
    }

    /** Applies the allowed/blocked domain filters (host suffix match, www-insensitive). */
    private @NonNull List<SearchResult> applyDomainFilters(
            @NonNull List<SearchResult> results, @NonNull SearchOptions options) {
        List<String> allowedDomains = options.allowedDomains();
        List<String> blockedDomains = options.blockedDomains();
        if ((allowedDomains == null || allowedDomains.isEmpty())
                && (blockedDomains == null || blockedDomains.isEmpty())) {
            return results;
        }
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult r : results) {
            String host = hostOf(r.url());
            if (host == null) {
                continue;
            }
            if (blockedDomains != null && matchesAny(host, blockedDomains)) {
                continue;
            }
            if (allowedDomains != null
                    && !allowedDomains.isEmpty()
                    && !matchesAny(host, allowedDomains)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static boolean matchesAny(@NonNull String host, @NonNull List<String> domains) {
        for (String d : domains) {
            String domain = normalizeDomain(d);
            if (!domain.isEmpty() && (host.equals(domain) || host.endsWith("." + domain))) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull String normalizeDomain(@NonNull String d) {
        String s = d.trim().toLowerCase();
        return s.startsWith("www.") ? s.substring("www.".length()) : s;
    }

    private static String hostOf(@NonNull String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
