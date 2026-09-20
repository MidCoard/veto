package top.focess.veto.secret.references;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class SecretRevealTest {
    @Test
    void revealRequiresExactOwnerSessionAndAgentAndLiveValue() {
        var store = new SecretCandidateStore();
        var scope = new SecretCandidateStore.Scope("owner", "session", "agent");
        String value = "SyntheticOnlyApiKey289174628394718239";
        var capture = store.capture(scope, "input", value);
        String ref = capture.candidates().getFirst().reference();
        assertEquals(value, store.reveal(scope, ref).orElseThrow());
        for (var foreign :
                java.util.List.of(
                        new SecretCandidateStore.Scope("other", "session", "agent"),
                        new SecretCandidateStore.Scope("owner", "other", "agent"),
                        new SecretCandidateStore.Scope("owner", "session", "other")))
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
                        .reveal(new SecretCandidateStore.Scope("o", "s", "a"), "missing")
                        .isEmpty());
    }
}
