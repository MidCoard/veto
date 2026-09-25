package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Session-scoped text boundary, invoked before text is recorded or sent to a model. */
@FunctionalInterface
public interface TextProtection {
    /**
     * Host-authenticated text scope.
     *
     * @param ownerId authenticated owner
     * @param sessionId selected session
     * @param agentId selected agent
     */
    record Scope(@NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    /**
     * Returns protected replacement text for one host-identified source.
     *
     * @param scope authenticated session scope
     * @param sourceId host-identified text source
     * @param text text to protect
     * @return protected replacement text
     */
    @NonNull String transform(@NonNull Scope scope, @NonNull String sourceId, @NonNull String text);
}
