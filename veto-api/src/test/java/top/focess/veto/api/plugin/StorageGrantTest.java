package top.focess.veto.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.storage.PluginStorage;

class StorageGrantTest {
    @Test
    void grantUsesSharedIdentityAndKeepsTokenSeparate() {
        var identity =
                new Scope.SessionScope(
                        UUID.fromString("4dc09c71-2ade-500b-b17f-1f44d942d780"), "session");
        var grant = new PluginStorage.Grant<Scope.@NonNull SessionScope>("issued-token", identity);
        assertSame(identity, grant.scope());
        assertEquals("issued-token", grant.token());
        assertEquals(
                new Scope.UserScope(UUID.fromString("4dc09c71-2ade-500b-b17f-1f44d942d780")),
                grant.scope().userScope());
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
                                "token",
                                new Scope.AgentScope(
                                        UUID.fromString("a59028e2-7be6-5c04-9439-9d4f7a4942b2"),
                                        "session",
                                        "agent")));
    }

    @Test
    void serviceIdentityCannotDisagreeWithStorageGrant() {
        var user =
                new PluginStorage.Grant<>(
                        "user-token",
                        new Scope.UserScope(
                                UUID.fromString("a59028e2-7be6-5c04-9439-9d4f7a4942b2")));
        var session =
                new PluginStorage.Grant<>(
                        "session-token",
                        new Scope.SessionScope(
                                UUID.fromString("a59028e2-7be6-5c04-9439-9d4f7a4942b2"),
                                "session"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller",
                                PluginScope.USER,
                                new Scope.UserScope(
                                        UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12")),
                                user));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller",
                                PluginScope.SESSION,
                                new Scope.SessionScope(
                                        UUID.fromString("a59028e2-7be6-5c04-9439-9d4f7a4942b2"),
                                        "other"),
                                session));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ServiceCallContext(
                                "caller",
                                PluginScope.AGENT,
                                new Scope.AgentScope(
                                        UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"),
                                        "session",
                                        "agent"),
                                session));
        var agent =
                new Scope.AgentScope(
                        UUID.fromString("a59028e2-7be6-5c04-9439-9d4f7a4942b2"),
                        "session",
                        "agent");
        assertSame(
                agent,
                new ServiceCallContext("caller", PluginScope.AGENT, agent, session).identity());
    }
}
