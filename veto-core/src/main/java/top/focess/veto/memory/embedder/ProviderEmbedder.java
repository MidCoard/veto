package top.focess.veto.memory.embedder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.credential.CredentialResolver;

/**
 * Provider-backed {@link Embedder} - calls a remote embeddings REST API (OpenAI-compatible or
 * Gemini) instead of the local {@link HashEmbedder} stub. Activated only when {@code
 * veto.memory.embedder.provider} is configured; otherwise {@link HashEmbedder} is used.
 *
 * <p>Uses JDK {@code HttpClient} (no extra dependency) and the provider's stable REST surface,
 * mirroring the DeepSeek client approach. The API key is resolved at call time from the {@link
 * top.focess.veto.vault.KeysteadVault} via {@link CredentialResolver}, so it never lives in config
 * or the instance. Failures throw {@link IllegalStateException} (best-effort memory is non-fatal to
 * the agent loop - callers swallow and continue, same as capture today).
 *
 * <p>Phase 1 supports {@code openai} (any OpenAI-compatible {@code /v1/embeddings} endpoint, incl.
 * OpenRouter/local) and {@code gemini}. A local ONNX embedder is the fast-follow.
 */
public final class ProviderEmbedder implements Embedder {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.memory.embedder.ProviderEmbedder");
    private static final @NonNull HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final @NonNull Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final @NonNull EmbedderProperties props;
    private final @NonNull CredentialResolver resolver;
    private final @NonNull ObjectMapper mapper;

    public ProviderEmbedder(
            @NonNull EmbedderProperties props,
            @NonNull CredentialResolver resolver,
            @NonNull ObjectMapper mapper) {
        this.props = props;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        String configuredProvider = props.getProvider();
        String provider = configuredProvider == null ? "" : configuredProvider.trim().toLowerCase();
        return switch (provider) {
            case "openai" -> embedOpenAi(text);
            case "gemini" -> embedGemini(text);
            default ->
                    throw new IllegalStateException(
                            "Unsupported veto.memory.embedder.provider: " + props.getProvider());
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
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            throwIfNotOk("OpenAI", resp);
            JsonNode vec = mapper.readTree(resp.body()).path("data").get(0).path("embedding");
            return toFloatArray(vec);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI embeddings call failed: " + e.getMessage(), e);
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
            URI uri = URI.create(base + "/v1beta/models/" + model + ":embedContent?key=" + apiKey);
            HttpRequest req = httpRequest(uri, body).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            throwIfNotOk("Gemini", resp);
            JsonNode vec = mapper.readTree(resp.body()).path("embedding").path("values");
            return toFloatArray(vec);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Gemini embeddings call failed: " + e.getMessage(), e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private @NonNull String resolveKey(@NonNull ProviderType type) {
        String key = props.getCredentialKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "veto.memory.embedder.credential-key is not set (provider=" + type + ")");
        }
        return resolver.resolve(type, key);
    }

    private @NonNull String requireModel() {
        String model = props.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("veto.memory.embedder.model is not set");
        }
        return model;
    }

    private static HttpRequest.@NonNull Builder httpRequest(
            @NonNull String uri, @NonNull String body) {
        return httpRequest(URI.create(uri), body);
    }

    private static HttpRequest.@NonNull Builder httpRequest(
            @NonNull URI uri, @NonNull String body) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static void throwIfNotOk(@NonNull String provider, @NonNull HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    provider
                            + " embeddings returned HTTP "
                            + resp.statusCode()
                            + ": "
                            + resp.body());
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
