package top.focess.veto.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeTest {
    @Test
    void identityPresenceFollowsScopeType() {
        Scope global = new Scope.GlobalScope();
        assertNull(global.userId());
        assertNull(global.session());
        assertNull(global.agent());

        Scope user = new Scope.UserScope(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"));
        assertEquals(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), user.userId());
        assertNull(user.session());
        assertNull(user.agent());

        Scope session =
                new Scope.SessionScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "session");
        assertEquals(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), session.userId());
        assertEquals("session", session.session());
        assertNull(session.agent());

        Scope agent =
                new Scope.AgentScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "agent");
        assertEquals(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), agent.userId());
        assertEquals("session", agent.session());
        assertEquals("agent", agent.agent());
        assertEquals(session, ((Scope.AgentScope) agent).sessionScope());
        assertEquals(user, ((Scope.AgentScope) agent).userScope());
        assertEquals(user, ((Scope.SessionScope) session).userScope());
    }

    @Test
    @SuppressWarnings(
            "argument") // WHY: deliberately violate the non-null contract to verify rejection.
    void requiredIdentityCannotBeMissing() {
        assertThrows(NullPointerException.class, () -> new Scope.UserScope(null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new Scope.SessionScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "session",
                                null));
    }
}
