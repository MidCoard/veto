package top.focess.veto.api.plugin.service;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Host-derived caller facts supplied to one admitted service invocation. Scope identities are
 * validated by the host; request JSON cannot choose or replace them. User and session IDs are
 * absent for a global call, and session ID is absent for a user call. Scoped service user
 * identities are immutable storage user IDs, rather than login names. AGENT calls require a
 * matching live host tool invocation in addition to a revalidated session grant; the identity value
 * itself conveys no authority.
 *
 * @param callerId host-attributed calling plugin ID, or empty for a host adapter
 * @param scope declared service scope
 * @param identity host-attributed global, user, session, or agent identity
 * @param storageGrant host-issued grant bound to the provider plugin's storage, if scoped
 */
public record ServiceCallContext(
        @NonNull String callerId,
        @NonNull PluginScope scope,
        @NonNull Scope identity,
        PluginStorage.Grant<?> storageGrant) {
    /** Checks that only facts valid for the declared scope are present. */
    public ServiceCallContext {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(identity, "identity");
        boolean valid =
                switch (scope) {
                    case APPLICATION ->
                            identity instanceof Scope.GlobalScope && storageGrant == null;
                    case USER ->
                            identity instanceof Scope.UserScope
                                    && storageGrant != null
                                    && identity.equals(storageGrant.scope());
                    case SESSION ->
                            identity instanceof Scope.SessionScope
                                    && storageGrant != null
                                    && identity.equals(storageGrant.scope());
                    case AGENT ->
                            identity instanceof Scope.AgentScope agent
                                    && storageGrant != null
                                    && agent.sessionScope().equals(storageGrant.scope());
                };
        if (!valid) throw new IllegalArgumentException("Invalid service call scope");
    }
}
