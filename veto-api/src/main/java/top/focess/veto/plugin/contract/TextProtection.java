package top.focess.veto.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Session-scoped text boundary, invoked before text is recorded or sent to a model. */
@FunctionalInterface
public interface TextProtection {
    record Scope(@NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    @NonNull String transform(@NonNull Scope scope, @NonNull String sourceId, @NonNull String text);
}
