package top.focess.veto.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.secret.references.SecretCandidateStore;

/** Browser-only access to an authorized agent scope's secret candidates. */
final class SecretCandidateFrontend implements FrontendContribution {
    private final @NonNull SecretCandidateStore candidates;

    SecretCandidateFrontend(@NonNull SecretCandidateStore candidates) {
        this.candidates = candidates;
    }

    @Override
    public @NonNull String module() {
        return frontendModule();
    }

    @Override
    public @NonNull JsonValue handle(
            Scope.@NonNull AgentScope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure {
        if (!action.equals("show")
                || !(arguments.values().get("reference") instanceof JsonValue.StringValue ref))
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        return candidates
                .reveal(scope, ref.value())
                .<JsonValue>map(JsonValue.StringValue::new)
                .orElse(JsonValue.NullValue.INSTANCE);
    }

    private static @NonNull String frontendModule() {
        try (var stream =
                SecretProtectionPlugin.class.getResourceAsStream(
                        "/top/focess/veto/secret/frontend.mjs")) {
            if (stream == null) throw new IllegalStateException("Missing frontend module");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load frontend module", failure);
        }
    }
}
