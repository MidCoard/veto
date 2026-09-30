package top.focess.veto.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScopeTest {
    @Test
    void identityPresenceFollowsScopeType() {
        Scope global = new Scope.GlobalScope();
        assertNull(global.owner());
        assertNull(global.session());
        assertNull(global.agent());

        Scope user = new Scope.UserScope("owner");
        assertEquals("owner", user.owner());
        assertNull(user.session());
        assertNull(user.agent());

        Scope session = new Scope.SessionScope("owner", "session");
        assertEquals("owner", session.owner());
        assertEquals("session", session.session());
        assertNull(session.agent());

        Scope agent = new Scope.AgentScope("owner", "session", "agent");
        assertEquals("owner", agent.owner());
        assertEquals("session", agent.session());
        assertEquals("agent", agent.agent());
    }

    @Test
    void requiredIdentityCannotBeMissing() {
        assertThrows(NullPointerException.class, () -> new Scope.UserScope(null));
        assertThrows(NullPointerException.class, () -> new Scope.SessionScope("owner", null));
        assertThrows(
                NullPointerException.class,
                () -> new Scope.AgentScope("owner", "session", null));
    }
}
