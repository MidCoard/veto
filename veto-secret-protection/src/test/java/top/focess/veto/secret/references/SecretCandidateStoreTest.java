package top.focess.veto.secret.references;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;

class SecretCandidateStoreTest {
    private static final Scope.@NonNull AgentScope OWNER =
            new Scope.AgentScope(UUID.randomUUID(), "session", "agent");

    @Test
    void capturesOnlyExactSelectionsAndReusesReferencesAcrossRepeatedValues() {
        var store = new SecretCandidateStore();
        String input = "😀 password=alpha token=beta; alpha";
        var first = store.captureExact(OWNER, "source", input, "alpha");
        var ref = first.candidates().getFirst().reference();
        assertEquals(
                "😀 password=[SECRET_REF:" + ref + "] token=beta; [SECRET_REF:" + ref + "]",
                first.text());
        assertEquals(input.indexOf("alpha"), first.candidates().getFirst().start());
        var second = store.captureExact(OWNER, "source", input, "beta");
        assertFalse(second.text().contains("alpha"));
        assertFalse(second.text().contains("beta"));
        assertEquals(ref, second.candidates().getFirst().reference());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.captureExact(OWNER, "source", input, "ALPHA"));
    }

    @Test
    void retainsTheEntireMultilineKeyIncludingDelimiters() {
        var store = new SecretCandidateStore();
        String key = "-----BEGIN PRIVATE KEY-----\r\nAbCdEf123+/=\r\n-----END PRIVATE KEY-----";
        var result = store.captureExact(OWNER, "file", "before\n" + key + "\nafter", key);
        assertEquals(
                key, store.reveal(OWNER, result.candidates().getFirst().reference()).orElseThrow());
        assertTrue(result.text().startsWith("before\n[SECRET_REF:"));
        assertTrue(result.text().endsWith("]\nafter"));
        var file = store.captureExact(OWNER, "file", "before\n" + key + "\nafter", key, true);
        assertEquals(4, file.text().chars().filter(character -> character == '\n').count());
    }

    @Test
    void capacityAndUtf8ByteLimitsRejectBeforeCommittingNewCandidates() {
        var store = new SecretCandidateStore(Clock.systemUTC(), Duration.ofMinutes(1), 1, 4, 4);
        assertThrows(
                IllegalStateException.class, () -> store.captureExact(OWNER, "s", "🔑x", "🔑x"));
        var first = store.captureExact(OWNER, "s", "abcd", "abcd");
        assertThrows(
                IllegalStateException.class, () -> store.captureExact(OWNER, "s", "efgh", "efgh"));
        assertEquals(
                "abcd",
                store.reveal(OWNER, first.candidates().getFirst().reference()).orElseThrow());
        assertEquals(first.text(), store.captureExact(OWNER, "s", "abcd", "abcd").text());
    }

    @Test
    void receiptsRequireLiveOwnershipAndCannotBeRebound() {
        var store = new SecretCandidateStore();
        var ref =
                store.captureExact(OWNER, "s", "secret", "secret")
                        .candidates()
                        .getFirst()
                        .reference();
        var receipt = new SecretCandidateStore.ImportReceipt("cred_example", "service", "label");
        for (var other :
                List.of(
                        new Scope.AgentScope(UUID.randomUUID(), OWNER.session(), OWNER.agent()),
                        new Scope.AgentScope(OWNER.userId(), "other", OWNER.agent()),
                        new Scope.AgentScope(OWNER.userId(), OWNER.session(), "other"))) {
            assertTrue(store.reveal(other, ref).isEmpty());
            assertThrows(
                    IllegalStateException.class, () -> store.recordImport(other, ref, receipt));
        }
        store.recordImport(OWNER, ref, receipt);
        store.recordImport(OWNER, ref, receipt);
        assertEquals("secret", store.reveal(OWNER, "cred_example").orElseThrow());
        assertEquals(
                SecretCandidateStore.State.IMPORTED,
                store.describe(OWNER, ref).orElseThrow().state());
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.recordImport(
                                OWNER,
                                ref,
                                new SecretCandidateStore.ImportReceipt(
                                        "cred_other", "service", "label")));
        store.discardAgent(OWNER);
        assertTrue(store.reveal(OWNER, ref).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.recordImport(OWNER, ref, receipt));
    }

    @Test
    void logoutAndRetiredSessionsRejectCaptureAndNeverReviveSecrets() {
        var store = new SecretCandidateStore();
        var ref =
                store.captureExact(OWNER, "s", "secret", "secret")
                        .candidates()
                        .getFirst()
                        .reference();
        store.closeUser(OWNER.userId());
        assertTrue(store.reveal(OWNER, ref).isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> store.captureExact(OWNER, "s", "secret", "secret"));
        store.openUser(OWNER.userId());
        assertTrue(store.reveal(OWNER, ref).isEmpty());
        store.captureExact(OWNER, "s", "secret", "secret");
        store.retireSession(OWNER.userId(), OWNER.session());
        assertThrows(
                IllegalStateException.class,
                () -> store.captureExact(OWNER, "s", "secret", "secret"));
    }

    @Test
    void expiresValuesAtTheDeadline() {
        var clock = new MutableClock();
        var store = new SecretCandidateStore(clock, Duration.ofSeconds(1), 4, 64, 32);
        var ref =
                store.captureExact(OWNER, "s", "secret", "secret")
                        .candidates()
                        .getFirst()
                        .reference();
        clock.current = Instant.EPOCH.plusSeconds(1);
        assertTrue(store.reveal(OWNER, ref).isEmpty());
        assertEquals(
                SecretCandidateStore.State.EXPIRED,
                store.describe(OWNER, ref).orElseThrow().state());
    }

    private static final class MutableClock extends Clock {
        private @NonNull Instant current = Instant.EPOCH;

        public @NonNull ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public @NonNull Clock withZone(@NonNull ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        public @NonNull Instant instant() {
            return current;
        }
    }
}
