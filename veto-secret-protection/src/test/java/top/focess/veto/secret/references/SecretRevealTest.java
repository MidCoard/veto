package top.focess.veto.secret.references;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;

class SecretRevealTest {
    @Test
    void revealRequiresExactOwnerSessionAndAgentAndLiveValue() {
        var store = new SecretCandidateStore();
        var scope =
                new Scope.AgentScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "agent");
        String value = "SyntheticOnlyApiKey289174628394718239";
        var capture = store.capture(scope, "input", value);
        String ref = capture.candidates().getFirst().reference();
        assertEquals(value, store.reveal(scope, ref).orElseThrow());
        for (var foreign :
                List.of(
                        new Scope.AgentScope(
                                UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"),
                                "session",
                                "agent"),
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "other",
                                "agent"),
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "session",
                                "other"))) assertTrue(store.reveal(foreign, ref).isEmpty());
        store.closeUser(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"));
        assertTrue(store.reveal(scope, ref).isEmpty());
        store.openUser(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"));
        assertTrue(store.reveal(scope, ref).isEmpty());
    }

    @Test
    void unknownReferenceCannotRevealAnything() {
        assertTrue(
                new SecretCandidateStore()
                        .reveal(
                                new Scope.AgentScope(
                                        UUID.fromString("0bd95b1d-8d21-50d2-bded-65305ba1e1ce"),
                                        "s",
                                        "a"),
                                "missing")
                        .isEmpty());
    }
}
