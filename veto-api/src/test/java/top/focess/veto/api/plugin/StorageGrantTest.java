package top.focess.veto.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.storage.PluginStorage;

class StorageGrantTest {
    @Test
    void grantUsesSharedIdentityAndKeepsTokenSeparate() {
        var identity = new Scope.SessionScope("immutable-user", "session");
        var grant = new PluginStorage.Grant<Scope.@NonNull SessionScope>("issued-token", identity);
        assertSame(identity, grant.scope());
        assertEquals("issued-token", grant.token());
        assertEquals(new Scope.UserScope("immutable-user"), grant.scope().userScope());
        var context = new ServiceCallContext("caller", PluginScope.SESSION, identity, grant);
        assertSame(identity, context.identity());
        var providerGrant = context.storageGrant();
        if (providerGrant == null) throw new AssertionError("Scoped context must have a grant");
        assertSame(grant, providerGrant);
    }

    @Test
    void storageSupportsOnlyUserAndSessionGrants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginStorage.Grant<>("token", new Scope.GlobalScope()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PluginStorage.Grant<>(
                                "token", new Scope.AgentScope("user", "session", "agent")));
    }

    @Test
    void serviceIdentityCannotDisagreeWithStorageGrant() {
        var user = new PluginStorage.Grant<>("user-token", new Scope.UserScope("user"));
        var session =
                new PluginStorage.Grant<>(
                        "session-token", new Scope.SessionScope("user", "session"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller", PluginScope.USER, new Scope.UserScope("other"), user));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller",
                                PluginScope.SESSION,
                                new Scope.SessionScope("user", "other"),
                                session));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller",
                                PluginScope.AGENT,
                                new Scope.AgentScope("other", "session", "agent"),
                                session));
        var agent = new Scope.AgentScope("user", "session", "agent");
        assertSame(
                agent,
                new ServiceCallContext("caller", PluginScope.AGENT, agent, session).identity());
    }
}
