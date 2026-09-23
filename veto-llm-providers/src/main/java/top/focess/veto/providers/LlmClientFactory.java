package top.focess.veto.providers;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.openai.client.OpenAIClient;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.PromptRenderer;

/**
 * Generic, type-safe cache for LLM SDK clients. SDK clients (the expensive part with OkHttp pools)
 * are built once per {@code (clientType, baseUrl, apiKey)} tuple and reused.
 *
 * <p>Convenience methods ({@link #openAi}, {@link #anthropic}, {@link #gemini}) wrap the cached SDK
 * client in a provider-specific {@link LlmClient} adapter — so the public API only ever returns our
 * own types, never a third-party SDK client.
 *
 * <p>Plugin providers register their own SDK types via {@link #register(Class, ClientBuilder)} and
 * use {@link #get(Class, String, String)} + their own {@link LlmClient} adapter.
 */
public class LlmClientFactory implements AutoCloseable {

    private final @NonNull ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> caches =
            new ConcurrentHashMap<>();

    private final @NonNull ConcurrentHashMap<Class<?>, ClientBuilder<?>> builders =
            new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface ClientBuilder<T> {
        @NonNull T build(String baseUrl, @NonNull String apiKey);
    }

    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull PromptRenderer prompts;

    /**
     * Constructs a new LlmClientFactory with the specified dependencies.
     *
     * @param objectMapper the mapper for JSON serialization (used by adapters)
     * @param prompts the native capability translator
     */
    public LlmClientFactory(@NonNull ObjectMapper objectMapper, @NonNull PromptRenderer prompts) {
        this.objectMapper = objectMapper;
        this.prompts = prompts;
    }

    // ── Generic (plugin-extensible) API ──────────────────────────────────────

    /**
     * Registers a builder for a client SDK type. Typically called once per type during plugin
     * initialization.
     *
     * @param <T> the client type
     * @param clientType the class of the client
     * @param builder a function that accepts {@code (baseUrl, apiKey)} and returns a new client
     */
    public <T> void register(@NonNull Class<T> clientType, @NonNull ClientBuilder<T> builder) {
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
    public <T> @NonNull T get(
            @NonNull Class<T> clientType, String baseUrl, @NonNull String apiKey) {
        ConcurrentHashMap<String, Object> cache = caches.get(clientType);
        if (cache == null) {
            throw new IllegalStateException(
                    "No builder registered for client type: " + clientType.getName());
        }
        ClientBuilder<T> builder = (ClientBuilder<T>) builders.get(clientType);
        if (builder == null) {
            throw new IllegalStateException(
                    "No builder registered for client type: " + clientType.getName());
        }
        String key = cacheKey(baseUrl, apiKey);
        T client = (T) cache.computeIfAbsent(key, k -> builder.build(baseUrl, apiKey));
        if (client == null) throw new IllegalStateException("Client cache returned null");
        return client;
    }

    // ── Convenience methods (return our own LlmClient, not SDK types) ────────

    /**
     * Returns an {@link LlmClient} backed by a cached {@code OpenAIClient}. For OpenAI and
     * providers that support strict {@code json_schema}.
     */
    public @NonNull LlmClient openAi(
            String baseUrl,
            @NonNull String apiKey,
            boolean supportsJsonSchema,
            @NonNull String providerName) {
        OpenAIClient sdk = get(OpenAIClient.class, baseUrl, apiKey);
        return new OpenAiLlmClient(sdk, supportsJsonSchema, providerName, objectMapper, prompts);
    }

    /**
     * Returns an {@link LlmClient} that speaks pure REST JSON to DeepSeek and other
     * OpenAI-compatible providers. No OpenAI SDK dependency — uses JDK {@code HttpClient} directly.
     */
    public @NonNull LlmClient deepSeek(
            String baseUrl, @NonNull String apiKey, @NonNull String providerName) {
        String endpoint =
                baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl;
        return new DeepSeekLlmClient(endpoint, apiKey, providerName, objectMapper, prompts);
    }

    /**
     * Returns an {@link LlmClient} backed by a cached {@code AnthropicClient}.
     *
     * @param baseUrl the base URL for the API
     * @param apiKey the API key for authentication
     * @return the LlmClient adapter
     */
    public @NonNull LlmClient anthropic(String baseUrl, @NonNull String apiKey) {
        AnthropicClient sdk = get(AnthropicClient.class, baseUrl, apiKey);
        return new AnthropicLlmClient(sdk, objectMapper, prompts);
    }

    /**
     * Returns an {@link LlmClient} backed by a cached Gemini {@code Client}.
     *
     * @param baseUrl the base URL for the API
     * @param apiKey the API key for authentication
     * @return the LlmClient adapter
     */
    public @NonNull LlmClient gemini(String baseUrl, @NonNull String apiKey) {
        Client sdk = get(Client.class, baseUrl, apiKey);
        return new GeminiLlmClient(sdk, objectMapper, prompts);
    }

    @Override
    public void close() {
        Exception failure = null;
        for (var cache : caches.values())
            for (var value : cache.values()) {
                if (value instanceof AutoCloseable client)
                    try {
                        client.close();
                    } catch (Exception error) {
                        if (error instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
            }
        caches.clear();
        if (failure != null) throw new IllegalStateException("SDK client cleanup failed", failure);
    }

    private static @NonNull String cacheKey(String baseUrl, @NonNull String apiKey) {
        return baseUrl + "|" + Integer.toHexString(apiKey.hashCode());
    }
}
