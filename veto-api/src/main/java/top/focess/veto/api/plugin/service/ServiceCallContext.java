package top.focess.veto.api.plugin.service;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Host-derived caller facts supplied to one admitted service invocation. Scope identities are
 * validated by the host; request JSON cannot choose or replace them. User and session IDs are
 * absent for a global call, and session ID is absent for a user call.
 *
 * @param callerId host-attributed calling plugin ID, or empty for a host adapter
 * @param scope declared service scope
 * @param userId validated stable user identity, if scoped
 * @param sessionId validated session identity, if session-scoped
 * @param storageScope host-issued scope bound to the provider plugin's storage, if scoped
 */
public record ServiceCallContext(
        @NonNull String callerId,
        @NonNull ServiceScope scope,
        String userId,
        String sessionId,
        PluginStorage.Scope storageScope) {
    /** Checks that only facts valid for the declared scope are present. */
    public ServiceCallContext {
        Objects.requireNonNull(scope, "scope");
        if (scope == ServiceScope.GLOBAL
                        && (userId != null || sessionId != null || storageScope != null)
                || scope == ServiceScope.USER
                        && (userId == null
                                || sessionId != null
                                || !(storageScope instanceof PluginStorage.UserScope))
                || scope == ServiceScope.SESSION
                        && (userId == null
                                || sessionId == null
                                || !(storageScope instanceof PluginStorage.SessionScope)))
            throw new IllegalArgumentException("Invalid service call scope");
    }
}
