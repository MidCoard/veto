package top.focess.veto.builtin.search;

import java.net.ProxySelector;
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
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keyless web search via DuckDuckGo's HTML endpoint — the default {@link SearchProvider} so {@code
 * web_search} works out of the box with no API key (mirrors how a hosted assistant shields the user
 * from key management). Best-effort: DuckDuckGo may throttle or reshape the page, in which case the
 * provider returns what it can parse (possibly empty) rather than failing the tool.
 */
public class DuckDuckGoSearchProvider implements SearchProvider, AutoCloseable {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.web.DuckDuckGoSearchProvider");
    private static final @NonNull String ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    // A browser-like UA; the default Java client UA is frequently blocked.
    private static final @NonNull String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0 Safari/537.36";

    private final @NonNull HttpClient httpClient;
    private final @NonNull String endpoint;

    /** Creates the provider with the default HTTP client and endpoint. */
    public DuckDuckGoSearchProvider() {
        this(createHttpClient(), ENDPOINT);
    }

    DuckDuckGoSearchProvider(@NonNull HttpClient httpClient, @NonNull String endpoint) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
    }

    private static @NonNull HttpClient createHttpClient() {
        HttpClient.@NonNull Builder builder =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector proxySelector = WebProxySelector.fromEnvironment();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        return builder.build();
    }

    @Override
    public @NonNull List<@NonNull SearchResult> search(
            @NonNull String query, @NonNull SearchOptions options) throws Exception {
        if (query.isBlank() || query.strip().length() < 2) {
            throw new IllegalArgumentException("query must be at least 2 characters");
        }
        String url = endpoint + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html")
                        .GET()
                        .build();
        var response =
                Objects.requireNonNull(
                        httpClient.<@NonNull String>send(
                                request, HttpResponse.BodyHandlers.ofString()),
                        "HttpClient.send returned null");
        if (response.statusCode() != 200) {
            log.warn("DuckDuckGo search returned HTTP {}", response.statusCode());
            throw new IllegalStateException(
                    "DuckDuckGo search failed: HTTP " + response.statusCode());
        }
        List<@NonNull SearchResult> results = parse(response.body());
        return SearchPolicy.apply(results, options);
    }

    @Override
    public @NonNull String name() {
        return "duckduckgo";
    }

    /** Parses DuckDuckGo's HTML results page into {@link SearchResult}s. */
    private @NonNull List<@NonNull SearchResult> parse(@NonNull String html) {
        List<@NonNull SearchResult> out = new ArrayList<>();
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

    @Override
    public void close() {
        httpClient.close();
    }
}
