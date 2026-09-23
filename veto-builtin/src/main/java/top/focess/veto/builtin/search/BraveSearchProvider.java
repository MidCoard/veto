package top.focess.veto.builtin.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.api.search.SearchResult;

/**
 * Brave Search API provider — higher-quality results than the keyless default, but requires an API
 * key ({@code veto.websearch.brave.api-key} or {@code BRAVE_API_KEY}). Enabled by setting {@code
 * veto.websearch.provider=brave}.
 */
public class BraveSearchProvider implements SearchProvider, AutoCloseable {

    private static final @NonNull String ENDPOINT =
            "https://api.search.brave.com/res/v1/web/search?q=";

    private final @NonNull HttpClient httpClient;
    private final @NonNull String endpoint;
    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private final @Nullable String apiKey;

    public BraveSearchProvider(@Nullable String apiKey) {
        this(
                apiKey,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                ENDPOINT);
    }

    BraveSearchProvider(
            @Nullable String apiKey, @NonNull HttpClient httpClient, @NonNull String endpoint) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.endpoint = endpoint;
    }

    @Override
    @SuppressWarnings("UastIncorrectHttpHeaderInspection")
    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "web_search provider is 'brave' but no API key is set - configure"
                            + " veto.websearch.brave.api-key (or BRAVE_API_KEY), or switch"
                            + " veto.websearch.provider to duckduckgo (keyless).");
        }
        if (query.isBlank() || query.strip().length() < 2) {
            throw new IllegalArgumentException("query must be at least 2 characters");
        }
        int cap = options.maxResults() > 0 ? options.maxResults() : 10;
        String url =
                endpoint
                        + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8)
                        + "&count="
                        + cap;
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", apiKey)
                        .GET()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Brave search failed: HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    @Override
    public @NonNull String name() {
        return "brave";
    }

    private @NonNull List<SearchResult> parse(@NonNull String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("web").path("results");
        List<SearchResult> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode r : results) {
                String title = r.path("title").asText("");
                String urlVal = r.path("url").asText("");
                String snippet = r.path("description").asText("");
                if (!title.isEmpty() && !urlVal.isEmpty()) {
                    out.add(new SearchResult(title, urlVal, snippet));
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
