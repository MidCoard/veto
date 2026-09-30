package top.focess.veto.api.plugin.service;

import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;

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
 * @param identity host-attributed global, user, session, or agent identity
 * @param storageScope host-issued scope bound to the provider plugin's storage, if scoped
 */
public record ServiceCallContext(
        @NonNull String callerId,
        @NonNull PluginScope scope,
        @NonNull Scope identity,
        PluginStorage.Scope storageScope) {
    /** Checks that only facts valid for the declared scope are present. */
    public ServiceCallContext {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(identity, "identity");
        if (scope == PluginScope.APPLICATION
                        && (!(identity instanceof Scope.GlobalScope) || storageScope != null)
                || scope == PluginScope.USER
                        && (!(identity instanceof Scope.UserScope)
                                || !(storageScope instanceof PluginStorage.UserScope))
                || scope == PluginScope.SESSION
                        && (!(identity instanceof Scope.SessionScope)
                                || !(storageScope instanceof PluginStorage.SessionScope))
                || scope == PluginScope.AGENT
                        && (!(identity instanceof Scope.AgentScope)
                                || !(storageScope instanceof PluginStorage.SessionScope)))
            throw new IllegalArgumentException("Invalid service call scope");
    }

    /** Authenticated owner, absent for global calls. */
    public String userId() {
        return identity.owner();
    }

    /** Authenticated session, absent for global and user calls. */
    public String sessionId() {
        return identity.session();
    }

    /** Authenticated agent, present only for agent calls. */
    public String agentId() {
        return identity.agent();
    }
}
