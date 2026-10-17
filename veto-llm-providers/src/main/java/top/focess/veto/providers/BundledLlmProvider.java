package top.focess.veto.providers;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmProvider;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;

/** Provider contribution using the plugin-owned client factory. */
final class BundledLlmProvider implements LlmProvider {
    private final @NonNull ProviderType type;
    private final @NonNull LlmClientFactory factory;

    BundledLlmProvider(@NonNull ProviderType type, @NonNull LlmClientFactory factory) {
        this.type = type;
        this.factory = factory;
    }

    @Override
    public @NonNull ProviderType type() {
        return type;
    }

    @Override
    public String defaultBaseUrl() {
        return type == ProviderType.DEEPSEEK ? "https://api.deepseek.com" : null;
    }

    @Override
    public LlmClient.@NonNull RawCompletion complete(@NonNull ResolvedRequest request)
            throws Exception {
        LlmClient client =
                switch (type) {
                    case OPENAI ->
                            factory.openAi(request.baseUrl(), request.apiKey(), true, "OpenAI");
                    case DEEPSEEK ->
                            factory.deepSeek(request.baseUrl(), request.apiKey(), "DeepSeek");
                    case ANTHROPIC -> factory.anthropic(request.baseUrl(), request.apiKey());
                    case GEMINI -> factory.gemini(request.baseUrl(), request.apiKey());
                };
        return client.complete(request);
    }
}
