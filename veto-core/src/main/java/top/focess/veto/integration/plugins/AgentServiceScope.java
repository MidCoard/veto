package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;

/** Derives agent identity only from the currently admitted host tool invocation. */
final class AgentServiceScope {
    private AgentServiceScope() {}

    static Scope.@NonNull AgentScope authorize(
            @NonNull PluginStorageFactory factory,
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        ToolCallContextHolder.requireEffects();
        var invocation = ToolCallContextHolder.get();
        if (invocation == null || Thread.currentThread().isInterrupted()) throw denied();
        var permit = invocation.executionPermit();
        var session = invocation.sessionId();
        if (session == null
                || !permit.callId().equals(ToolCallContextHolder.currentCallId())
                || !permit.authorizesCaller(invocation)
                || !session.toString().equals(grant.scope().session())
                || !factory.authorizeSession(caller, grant).equals(invocation.owner()))
            throw denied();
        // Storage grants use immutable user IDs; the live caller uses the login name.
        return new Scope.AgentScope(
                grant.scope().owner(), grant.scope().session(), invocation.agentId());
    }

    private static @NonNull SecurityException denied() {
        return new SecurityException("Agent service requires a matching admitted tool invocation");
    }
}
