package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** A plugin-owned browser module and its scoped backend actions. */
public abstract class FrontendContribution {
    /** Constructs a frontend aspect. */
    protected FrontendContribution() {}

    /**
     * Returns the complete browser ESM source.
     *
     * @return the complete browser ESM source
     */
    public abstract @NonNull String module();

    /**
     * Handles a host-authorized action from this module.
     *
     * @param scope authenticated owner, session, and agent
     * @param action action name requested by the module
     * @param arguments bounded JSON arguments
     * @return bounded JSON response
     * @throws PluginFailure when the action cannot be completed
     */
    public abstract @NonNull JsonValue handle(
            @NonNull Scope scope, @NonNull String action, JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure;

    /**
     * Returns handler view used by host action dispatch.
     *
     * @return handler view used by host action dispatch
     */
    public final @NonNull Handler handler() {
        return this::handle;
    }

    /**
     * Host-authenticated frontend action scope.
     *
     * @param ownerId authenticated user identity
     * @param sessionId selected session identity
     * @param agentId calling agent identity
     */
    public record Scope(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    /** Functional action callback used by host action dispatch. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Handles one scoped frontend action.
         *
         * @param scope authenticated owner, session, and agent
         * @param action action name requested by the module
         * @param arguments bounded JSON arguments
         * @return bounded JSON response
         * @throws PluginFailure when the action cannot be completed
         */
        @NonNull JsonValue handle(
                @NonNull Scope scope,
                @NonNull String action,
                JsonValue.@NonNull ObjectValue arguments)
                throws PluginFailure;
    }
}
