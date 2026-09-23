package top.focess.veto.providers;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.jspecify.annotations.NonNull;

/**
 * Registers the bundled SDK builders during plugin initialization; no Spring container is required.
 */
public class LlmClientRegistration {

    private final @NonNull LlmClientFactory factory;

    /**
     * Constructs a new LlmClientRegistration with the specified factory.
     *
     * @param factory the factory to register builders with
     */
    public LlmClientRegistration(@NonNull LlmClientFactory factory) {
        this.factory = factory;
    }

    /** Registers the three built-in SDK builder functions. */
    public void registerBuilders() {
        factory.register(
                OpenAIClient.class,
                (baseUrl, apiKey) -> {
                    OpenAIOkHttpClient.Builder builder =
                            OpenAIOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });

        factory.register(
                AnthropicClient.class,
                (baseUrl, apiKey) -> {
                    AnthropicOkHttpClient.Builder builder =
                            AnthropicOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });

        factory.register(
                Client.class,
                (baseUrl, apiKey) -> {
                    Client.Builder builder = Client.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isEmpty()) {
                        builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
                    }
                    return builder.build();
                });
    }
}
