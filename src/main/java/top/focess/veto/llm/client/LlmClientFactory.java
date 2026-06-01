package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.schema.SchemaNormalizerService;

/**
 * Generic, type-safe cache for LLM SDK clients. SDK clients (the expensive part with OkHttp pools)
 * are built once per {@code (clientType, baseUrl, apiKey)} tuple and reused.
 *
 * <p>Convenience methods ({@link #openAi}, {@link #anthropic}, {@link #gemini}) wrap the cached SDK
 * client in a provider-specific {@link LlmClient} adapter — so the public API only ever returns our
 * own types, never a third-party SDK client.
 *
 * <p>Plugin providers register their own SDK types via {@link #register(Class, BiFunction)} and use
 * {@link #get(Class, String, String)} + their own {@link LlmClient} adapter.
 */
@Component
public class LlmClientFactory {

    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> caches =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Class<?>, BiFunction<String, String, ?>> builders =
            new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final SchemaNormalizerService schemaNormalizer;

    /**
     * Constructs a new LlmClientFactory with the specified dependencies.
     *
     * @param objectMapper     the mapper for JSON serialization (used by adapters)
     * @param schemaNormalizer the service for normalizing schemas (used by adapters)
     */
    public LlmClientFactory(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            SchemaNormalizerService schemaNormalizer) {
        this.objectMapper = objectMapper;
        this.schemaNormalizer = schemaNormalizer;
    }

    // ── Generic (plugin-extensible) API ──────────────────────────────────────

    /**
     * Registers a builder for a client SDK type. Typically called once per type from a
     * {@code @Configuration} class.
     *
     * @param <T>        the client type
     * @param clientType the class of the client
     * @param builder    a function that accepts {@code (baseUrl, apiKey)} and returns a new client
     */
    public <T> void register(Class<T> clientType, BiFunction<String, String, T> builder) {
        builders.compute(
                clientType,
                (k, existing) -> {
                    if (existing != null && existing != builder) {
                        throw new IllegalStateException(
                                "Builder already registered for client type: "
                                        + clientType.getName());
                    }
                    return builder;
                });
        caches.computeIfAbsent(clientType, k -> new ConcurrentHashMap<>());
    }

    /**
     * Returns a cached or newly-built SDK client of the given type. Plugins use this directly;
     * built-in providers use the convenience methods.
     *
     * @param <T> the client type
     * @param clientType the class of the client
     * @param baseUrl the base URL for the API, or {@code null} to use the SDK default
     * @param apiKey the API key for authentication
     * @return the client instance
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> clientType, String baseUrl, String apiKey) {
        ConcurrentHashMap<String, Object> cache = caches.get(clientType);
        if (cache == null) {
            throw new IllegalStateException(
                    "No builder registered for client type: " + clientType.getName());
        }
        BiFunction<String, String, T> builder =
                (BiFunction<String, String, T>) builders.get(clientType);
        String key = cacheKey(baseUrl, apiKey);
        return (T) cache.computeIfAbsent(key, k -> builder.apply(baseUrl, apiKey));
    }

    // ── Convenience methods (return our own LlmClient, not SDK types) ────────

    /**
     * Returns an {@link LlmClient} backed by a cached {@code OpenAIClient}. Shared by OpenAI,
     * DeepSeek, and any OpenAI-compatible provider.
     *
     * @param baseUrl the base URL for the API
     * @param apiKey the API key for authentication
     * @param supportsJsonSchema whether the provider supports strict {@code json_schema}
     * @param providerName the human-readable provider name for error messages
     * @return the LlmClient adapter
     */
    public LlmClient openAi(
            String baseUrl, String apiKey, boolean supportsJsonSchema, String providerName) {
        com.openai.client.OpenAIClient sdk =
                get(com.openai.client.OpenAIClient.class, baseUrl, apiKey);
        return new OpenAiLlmClient(
                sdk, supportsJsonSchema, providerName, objectMapper, schemaNormalizer);
    }

    /**
     * Returns an {@link LlmClient} backed by a cached {@code AnthropicClient}.
     *
     * @param baseUrl the base URL for the API
     * @param apiKey the API key for authentication
     * @return the LlmClient adapter
     */
    public LlmClient anthropic(String baseUrl, String apiKey) {
        com.anthropic.client.AnthropicClient sdk =
                get(com.anthropic.client.AnthropicClient.class, baseUrl, apiKey);
        return new AnthropicLlmClient(sdk, schemaNormalizer);
    }

    /**
     * Returns an {@link LlmClient} backed by a cached Gemini {@code Client}.
     *
     * @param baseUrl the base URL for the API
     * @param apiKey  the API key for authentication
     * @return the LlmClient adapter
     */
    public LlmClient gemini(String baseUrl, String apiKey) {
        com.google.genai.Client sdk = get(com.google.genai.Client.class, baseUrl, apiKey);
        return new GeminiLlmClient(sdk, objectMapper, schemaNormalizer);
    }

    private static String cacheKey(String baseUrl, String apiKey) {
        String base = baseUrl == null ? "" : baseUrl;
        return base + "|" + Integer.toHexString(apiKey == null ? 0 : apiKey.hashCode());
    }
}
