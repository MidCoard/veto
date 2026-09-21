package top.focess.veto.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Trusted, self-contained browser ESM plus its session-scoped backend actions. */
public record FrontendContribution(@NonNull String module, @NonNull Handler handler) {
    public FrontendContribution {
        if (module.isBlank() || module.length() > 1048576)
            throw new IllegalArgumentException("Invalid frontend module size");
    }

    public record Scope(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    @FunctionalInterface
    public interface Handler {
        @NonNull JsonValue handle(
                @NonNull Scope scope,
                @NonNull String action,
                JsonValue.@NonNull ObjectValue arguments)
                throws PluginFailure;
    }
}
