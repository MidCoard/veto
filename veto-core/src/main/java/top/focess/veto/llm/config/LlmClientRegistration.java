package top.focess.veto.llm.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.llm.client.LlmClientFactory;

/**
 * Registers the built-in LLM SDK client builders with {@link LlmClientFactory}. This is the
 * <b>only</b> place in the core codebase that knows how to instantiate specific SDK clients.
 *
 * <p>Plugin providers register their own builders the same way — call {@code
 * factory.register(MyClient.class, myBuilder)} in the plugin's {@code @Configuration} and then use
 * {@code factory.get(MyClient.class, baseUrl, apiKey)} in the provider.
 */
@Configuration
public class LlmClientRegistration {

    private final LlmClientFactory factory;

    /**
     * Constructs a new LlmClientRegistration with the specified factory.
     *
     * @param factory the factory to register builders with
     */
    public LlmClientRegistration(LlmClientFactory factory) {
        this.factory = factory;
    }

    /** Registers the three built-in SDK builder functions. */
    @PostConstruct
    public void registerBuilders() {
        factory.register(
                OpenAIClient.class,
                (baseUrl, apiKey) -> {
                    OpenAIOkHttpClient.Builder builder =
                            OpenAIOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });

        factory.register(
                AnthropicClient.class,
                (baseUrl, apiKey) -> {
                    AnthropicOkHttpClient.Builder builder =
                            AnthropicOkHttpClient.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    return builder.build();
                });

        factory.register(
                Client.class,
                (baseUrl, apiKey) -> {
                    Client.Builder builder = Client.builder().apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
                    }
                    return builder.build();
                });
    }
}
