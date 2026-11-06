package top.focess.veto.providers;

import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmProvider;
import top.focess.veto.api.llm.LlmRequestResolver;
import top.focess.veto.api.llm.LocalModelRuntime;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.VetoRequest;

/** Ordinary provider whose endpoint is a local model artifact, not an HTTP URL. */
final class LocalLlmProvider implements LlmProvider {
    private final @NonNull LlmClientFactory clients;
    private final @NonNull Supplier<@NonNull LocalModelRuntime> runtime;

    LocalLlmProvider(
            @NonNull LlmClientFactory clients,
            @NonNull Supplier<@NonNull LocalModelRuntime> runtime) {
        this.clients = clients;
        this.runtime = runtime;
    }

    @Override
    public @NonNull ProviderType type() {
        return ProviderType.LOCAL;
    }

    @Override
    public String defaultBaseUrl() {
        return null;
    }

    @Override
    public @NonNull ResolvedRequest resolve(
            @NonNull VetoRequest request, @NonNull LlmRequestResolver network) {
        var path = request.baseUrl();
        if (path == null || path.isBlank())
            throw new IllegalArgumentException("LOCAL requires a model file endpoint");
        if (!request.credentialKey().isEmpty())
            throw new IllegalArgumentException("LOCAL does not accept a network credential");
        return new ResolvedRequest(request, path, "");
    }

    @Override
    public LlmClient.@NonNull RawCompletion complete(@NonNull ResolvedRequest request)
            throws Exception {
        var path = request.baseUrl();
        if (path == null) throw new IllegalArgumentException("Missing model file endpoint");
        var context = request.options().contextWindowTokens();
        String endpoint = runtime.get().endpoint(path, context == null ? 4096 : context);
        var transport = new ResolvedRequest(request.request(), endpoint, "local");
        return clients.openAi(endpoint, "local", true, "Local").complete(transport);
    }
}
