package top.focess.veto.llm.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.llm.credential.CredentialResolver;

/** Generic provider embedding transport with fixed configuration, bounded input and response. */
public final class ProviderEmbeddingClient implements TextEmbedding {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger(ToolDocs.nonNullClass(ProviderEmbeddingClient.class));
    private static final @NonNull HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final @NonNull Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final @NonNull Duration timeout;
    private final @NonNull EmbeddingProfile props;
    private final @NonNull CredentialResolver resolver;
    private final @NonNull ObjectMapper mapper;

    /** Creates a client with the default 30-second request timeout. */
    public ProviderEmbeddingClient(
            @NonNull EmbeddingProfile props,
            @NonNull CredentialResolver resolver,
            @NonNull ObjectMapper mapper) {
        this(props, resolver, mapper, REQUEST_TIMEOUT);
    }

    ProviderEmbeddingClient(
            @NonNull EmbeddingProfile props,
            @NonNull CredentialResolver resolver,
            @NonNull ObjectMapper mapper,
            @NonNull Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(REQUEST_TIMEOUT) > 0)
            throw new IllegalArgumentException("Invalid embedding timeout");
        this.timeout = timeout;
        if (props.getDimension() < 1 || props.getDimension() > 65536)
            throw new IllegalArgumentException("Invalid embedding dimension");
        this.props = props;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        if (text.length() > 64_000) throw new IllegalArgumentException("Embedding input too large");
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Embedding cancelled");
        String configuredProvider = props.getProvider();
        String provider = configuredProvider == null ? "" : configuredProvider.trim().toLowerCase();
        return switch (provider) {
            case "openai" -> embedOpenAi(text);
            case "gemini" -> embedGemini(text);
            default ->
                    throw new IllegalStateException(
                            "Unsupported embedding profile.provider: " + props.getProvider());
        };
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    // ── OpenAI-compatible (/v1/embeddings) ──────────────────────────────────

    private float @NonNull [] embedOpenAi(@NonNull String text) {
        String apiKey = resolveKey(ProviderType.OPENAI);
        String base = defaultIfBlank(props.getBaseUrl(), "https://api.openai.com");
        String model = requireModel();
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest req =
                    httpRequest(base + "/v1/embeddings", body)
                            .header("Authorization", "Bearer " + apiKey)
                            .build();
            JsonNode vec = read(req).path("data").path(0).path("embedding");
            return toFloatArray(vec);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Embedding request failed");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Embedding cancelled");
            }
            throw new IllegalStateException("Embedding request failed");
        }
    }

    // ── Gemini (:embedContent) ──────────────────────────────────────────────

    private float @NonNull [] embedGemini(@NonNull String text) {
        String apiKey = resolveKey(ProviderType.GEMINI);
        String base =
                defaultIfBlank(props.getBaseUrl(), "https://generativelanguage.googleapis.com");
        String model = requireModel();
        try {
            String body =
                    mapper.writeValueAsString(
                            Map.of("content", Map.of("parts", List.of(Map.of("text", text)))));
            URI uri = URI.create(base + "/v1beta/models/" + model + ":embedContent");
            HttpRequest req = httpRequest(uri, body).header("x-goog-api-key", apiKey).build();
            JsonNode vec = read(req).path("embedding").path("values");
            return toFloatArray(vec);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Embedding request failed");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Embedding cancelled");
            }
            throw new IllegalStateException("Embedding request failed");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private @NonNull String resolveKey(@NonNull ProviderType type) {
        String key = props.getCredentialKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "embedding profile.credential-key is not set (provider=" + type + ")");
        }
        return resolver.resolve(type, key);
    }

    private @NonNull String requireModel() {
        String model = props.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("embedding profile.model is not set");
        }
        return model;
    }

    private HttpRequest.@NonNull Builder httpRequest(@NonNull String uri, @NonNull String body) {
        return httpRequest(URI.create(uri), body);
    }

    private HttpRequest.@NonNull Builder httpRequest(@NonNull URI uri, @NonNull String body) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private @NonNull JsonNode read(@NonNull HttpRequest request) throws Exception {
        var pending = HTTP.sendAsync(request, ignored -> new BoundedEmbeddingBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200)
                throw new IllegalStateException(
                        "Embedding provider returned HTTP " + response.statusCode());
            JsonNode result = mapper.readTree(response.body());
            if (result == null) throw new IllegalStateException("Embedding response empty");
            return result;
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static float @NonNull [] toFloatArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("Provider returned an empty embedding");
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    private static @NonNull String defaultIfBlank(String value, @NonNull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
