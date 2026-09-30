package top.focess.veto.secret.references;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;

class SecretRevealTest {
    @Test
    void revealRequiresExactOwnerSessionAndAgentAndLiveValue() {
        var store = new SecretCandidateStore();
        var scope = new Scope.AgentScope("owner", "session", "agent");
        String value = "SyntheticOnlyApiKey289174628394718239";
        var capture = store.capture(scope, "input", value);
        String ref = capture.candidates().getFirst().reference();
        assertEquals(value, store.reveal(scope, ref).orElseThrow());
        for (var foreign :
                List.of(
                        new Scope.AgentScope("other", "session", "agent"),
                        new Scope.AgentScope("owner", "other", "agent"),
                        new Scope.AgentScope("owner", "session", "other")))
            assertTrue(store.reveal(foreign, ref).isEmpty());
        store.closeOwner("owner");
        assertTrue(store.reveal(scope, ref).isEmpty());
        store.openOwner("owner");
        assertTrue(store.reveal(scope, ref).isEmpty());
    }

    @Test
    void unknownReferenceCannotRevealAnything() {
        assertTrue(
                new SecretCandidateStore()
                        .reveal(new Scope.AgentScope("o", "s", "a"), "missing")
                        .isEmpty());
    }
}
