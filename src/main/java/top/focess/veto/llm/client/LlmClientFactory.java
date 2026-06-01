package top.focess.veto.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Caches SDK clients so they are built once per {@code (baseUrl, apiKey)} pair instead of on every
 * call. Each SDK client owns its own OkHttp connection/thread pools; rebuilding them per request
 * (as the original providers did) churns sockets and threads under load.
 */
@Component
public class LlmClientFactory {
    private final ConcurrentHashMap<String, OpenAIClient> openAiClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AnthropicClient> anthropicClients =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Client> geminiClients = new ConcurrentHashMap<>();

    /**
     * Returns a cached or new OpenAIClient for the given base URL and API key.
     *
     * @param baseUrl the base URL of the OpenAI API
     * @param apiKey  the API key for authentication
     * @return the OpenAIClient instance
     */
    public OpenAIClient openAi(String baseUrl, String apiKey) {
        return openAiClients.computeIfAbsent(
                cacheKey(baseUrl, apiKey),
                k -> {
                    OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });
    }

    /**
     * Returns a cached or new AnthropicClient for the given base URL and API key.
     *
     * @param baseUrl the base URL of the Anthropic API
     * @param apiKey  the API key for authentication
     * @return the AnthropicClient instance
     */
    public AnthropicClient anthropic(String baseUrl, String apiKey) {
        return anthropicClients.computeIfAbsent(
                cacheKey(baseUrl, apiKey),
                k -> {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });
    }

    /**
     * Returns a cached or new Gemini Client for the given base URL and API key.
     *
     * @param baseUrl the base URL of the Gemini API
     * @param apiKey  the API key for authentication
     * @return the Gemini Client instance
     */
    public Client gemini(String baseUrl, String apiKey) {
        return geminiClients.computeIfAbsent(
                cacheKey(baseUrl, apiKey),
                k -> {
                    Client.Builder builder = Client.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
                    }
                    return builder.build();
                });
    }

    private static String cacheKey(String baseUrl, String apiKey) {
        String base = baseUrl == null ? "" : baseUrl;
        // Hash the secret so it is never stored verbatim as a map key.
        return base + "|" + Integer.toHexString(apiKey == null ? 0 : apiKey.hashCode());
    }
}
