package top.focess.veto.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.*;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;

/** Bundled provider transports, using only the public plugin API and vendor SDKs. */
public final class LlmProvidersPlugin extends VetoPlugin {
    private final @NonNull LlmClientFactory clients;

    /** Constructs the provider contributions from the bound host services. */
    public LlmProvidersPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        clients = createFactory(context);
        new LlmClientRegistration(clients).registerBuilders();
        for (ProviderType type : EnumSet.allOf(ProviderType.class))
            context.register(
                    StandardContributionPoints.LLM_PROVIDERS,
                    type.name().toLowerCase(Locale.ROOT),
                    new Provider(type, clients));
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.llm-providers", "1.0.100");
    }

    @Override
    public @NonNull String displayName() {
        return "LLM Providers";
    }

    private static @NonNull LlmClientFactory createFactory(@NonNull PluginContext context) {
        PromptRenderer prompts =
                (source, data) ->
                        context.service(PromptRenderer.class)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Host prompt compiler unavailable"))
                                .compile(source, data);
        return new LlmClientFactory(new ObjectMapper(), prompts);
    }

    private static final class Provider implements LlmProvider {
        private final @NonNull ProviderType type;
        private final @NonNull LlmClientFactory factory;

        private Provider(@NonNull ProviderType type, @NonNull LlmClientFactory factory) {
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

    @Override
    public void start() {}

    @Override
    public void close() {
        clients.close();
    }
}
