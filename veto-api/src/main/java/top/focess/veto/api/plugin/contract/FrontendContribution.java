package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Trusted, self-contained browser ESM plus its session-scoped backend actions.
 *
 * @param module complete browser module source
 * @param handler backend action handler for the module
 */
public record FrontendContribution(@NonNull String module, @NonNull Handler handler) {
    /** Validates the browser module payload. */
    public FrontendContribution {
        if (module.isBlank() || module.length() > 1048576)
            throw new IllegalArgumentException("Invalid frontend module size");
    }

    /**
     * Host-authenticated frontend action scope.
     *
     * @param ownerId authenticated owner
     * @param sessionId selected session
     * @param agentId selected agent
     */
    public record Scope(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    /** Handles backend actions requested by this contribution's browser module. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Handles one host-authorized module action.
         *
         * @param scope authenticated frontend action scope
         * @param action module-defined action name
         * @param arguments bounded action arguments
         * @return the bounded JSON response
         * @throws PluginFailure when the action fails with a public plugin error
         */
        @NonNull JsonValue handle(
                @NonNull Scope scope,
                @NonNull String action,
                JsonValue.@NonNull ObjectValue arguments)
                throws PluginFailure;
    }
}
