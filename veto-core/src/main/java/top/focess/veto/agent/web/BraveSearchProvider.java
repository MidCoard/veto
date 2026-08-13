package top.focess.veto.agent.web;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Brave Search API provider — higher-quality results than the keyless default, but requires an API
 * key ({@code veto.websearch.brave.api-key} or {@code BRAVE_API_KEY}). Enabled by setting {@code
 * veto.websearch.provider=brave}.
 */
@Component
@ConditionalOnProperty(name = "veto.websearch.provider", havingValue = "brave")
public class BraveSearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search?q=";

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public BraveSearchProvider(
            @Value("${veto.websearch.brave.api-key:${BRAVE_API_KEY:}}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "web_search provider is 'brave' but no API key is set - configure"
                            + " veto.websearch.brave.api-key (or BRAVE_API_KEY), or switch"
                            + " veto.websearch.provider to duckduckgo (keyless).");
        }
        if (query.isBlank() || query.strip().length() < 2) {
            throw new IllegalArgumentException("web_search query must be at least 2 characters");
        }
        int cap = options.maxResults() > 0 ? options.maxResults() : 10;
        String url =
                ENDPOINT
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
}
