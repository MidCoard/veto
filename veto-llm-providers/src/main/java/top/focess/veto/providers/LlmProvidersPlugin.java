package top.focess.veto.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.*;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.Contribution;

/** Bundled provider transports, using only the public plugin API and vendor SDKs. */
public final class LlmProvidersPlugin extends AbstractVetoPlugin {
    private LlmClientFactory clients;

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.llm-providers", "1.0.100");
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        PromptRenderer prompts =
                (source, data) ->
                        context.service(ToolDocs.nonNullClass(PromptRenderer.class))
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Host prompt compiler unavailable"))
                                .compile(source, data);
        var factory = new LlmClientFactory(new ObjectMapper(), prompts);
        clients = factory;
        new LlmClientRegistration(factory).registerBuilders();
        return new PluginContributions(
                EnumSet.allOf(ToolDocs.nonNullClass(ProviderType.class)).stream()
                        .<Contribution<?>>map(
                                type ->
                                        Contribution.of(
                                                StandardContributionPoints.LLM_PROVIDERS,
                                                type.name().toLowerCase(Locale.ROOT),
                                                new Provider(type, factory)))
                        .toList());
    }

    private record Provider(@NonNull ProviderType type, @NonNull LlmClientFactory factory)
            implements LlmProvider {
        public String defaultBaseUrl() {
            return type == ProviderType.DEEPSEEK ? "https://api.deepseek.com" : null;
        }

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
    protected void onStart() {}

    @Override
    protected void onClose() throws Exception {
        if (clients != null) clients.close();
    }
}
