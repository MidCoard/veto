package top.focess.veto.secret.references;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.secret.api.CredentialWriter;

class SecretCandidateStoreTest {
    @Test
    void fileCapturePreservesSourceLinesAndOriginalPrivateKeyOffsets() {
        var store = new SecretCandidateStore();
        String key = "-----BEGIN PRIVATE KEY-----\r\nsynthetic-material\n-----END PRIVATE KEY-----";
        String source = "first\r\n" + key + "\r\nlast\n";
        var captured = store.captureFile(scope, "file-observation", source);
        var descriptor = captured.candidates().getFirst();
        assertEquals(7, descriptor.start());
        assertEquals(7 + key.length(), descriptor.end());
        String reference = "[SECRET_REF:" + descriptor.reference() + "]";
        assertEquals("first\r\n" + reference + "\r\n\n\r\nlast\n", captured.text());
        assertFalse(captured.text().contains("synthetic-material"));
        assertEquals(captured.text(), store.captureFile(scope, "repeat", captured.text()).text());
        assertEquals(reference, store.capture(scope, "user", key).text());
    }

    @Test
    void importMarksSuccessOnlyAfterStorageAndRedactsStorageFailures() {
        var store = new SecretCandidateStore();
        String reference =
                store.capture(scope, "source", "password=synthetic-token")
                        .candidates()
                        .getFirst()
                        .reference();
        var writer = new InMemoryWriter();
        writer.failNextWrite = true;
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.importOnce(scope, reference, "github", "Repository", writer));
        assertEquals("Credential import could not be saved", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(
                SecretCandidateStore.State.AVAILABLE,
                store.describe(scope, reference).orElseThrow().state());
        var imported = store.importOnce(scope, reference, "github", "Repository", writer);
        assertEquals(
                SecretCandidateStore.State.IMPORTED,
                store.describe(scope, reference).orElseThrow().state());
        assertEquals(imported, store.importOnce(scope, reference, "github", "Repository", writer));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.importOnce(scope, reference, "github", "Changed", writer));
        var expected =
                new StoredCredential("alice", reference, "github", "Repository", "synthetic-token");
        assertEquals(List.of(expected, expected), writer.attempts);
        writer.unlocked = false;
        assertThrows(
                IllegalStateException.class,
                () -> store.importOnce(scope, reference, "github", "Repository", writer));
    }

    private final SecretCandidateStore.@NonNull Scope scope =
            new SecretCandidateStore.Scope("alice", "session", "agent");

    @Test
    void stableReferencesReplaceKnownValuesWithoutPublishingRawSecrets() {
        var store = new SecretCandidateStore();
        var first =
                store.capture(scope, "observation-1", "😀 password=alpha-secret token=beta-secret");
        assertEquals(2, first.candidates().size());
        assertFalse(first.text().contains("alpha-secret"));
        assertFalse(first.text().contains("beta-secret"));
        assertFalse(first.candidates().toString().contains("alpha-secret"));
        String reference = first.candidates().getFirst().reference();
        var repeated =
                store.capture(scope, "observation-2", "alpha-secret appears twice: alpha-secret");
        assertEquals(2, repeated.candidates().size());
        assertTrue(
                repeated.candidates().stream()
                        .allMatch(candidate -> candidate.reference().equals(reference)));
        assertFalse(repeated.text().contains("alpha-secret"));
        assertEquals(
                SecretCandidateStore.State.AVAILABLE,
                store.describe(scope, reference).orElseThrow().state());
    }

    @Test
    void ownerSessionAndAgentCannotShareOrForgeReferences() {
        var store = new SecretCandidateStore();
        String reference =
                store.capture(scope, "source", "password=alpha-secret")
                        .candidates()
                        .getFirst()
                        .reference();
        for (var other :
                new SecretCandidateStore.Scope[] {
                    new SecretCandidateStore.Scope("bob", "session", "agent"),
                    new SecretCandidateStore.Scope("alice", "other", "agent"),
                    new SecretCandidateStore.Scope("alice", "session", "mate")
                }) {
            assertTrue(store.describe(other, reference).isEmpty());
            assertNotEquals(
                    reference,
                    store.capture(other, "source", "password=alpha-secret")
                            .candidates()
                            .getFirst()
                            .reference());
        }
        assertTrue(store.describe(scope, "s_forged").isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> store.capture(scope, "source", "[SECRET_REF:s_forged]"));
        String marker = "[SECRET_REF:" + reference + "]";
        assertEquals(marker, store.capture(scope, "source", marker).text());
        assertTrue(store.capture(scope, "source", marker).candidates().isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> store.capture(scope, "source", "password=unprotected" + marker));
    }

    @Test
    void rejectedBatchDoesNotConsumeCapacityAndUtf8BytesAreEnforced() {
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var store = new SecretCandidateStore(clock, Duration.ofMinutes(30), 1, 10, 10);
        assertThrows(
                IllegalStateException.class,
                () -> store.capture(scope, "source", "password=alpha token=beta"));
        assertEquals(1, store.capture(scope, "source", "password=alpha").candidates().size());
        assertEquals(2, store.capture(scope, "source", "alpha alpha").candidates().size());
        var byteLimited = new SecretCandidateStore(clock, Duration.ofMinutes(30), 5, 3, 3);
        assertThrows(
                IllegalStateException.class,
                () -> byteLimited.capture(scope, "source", "password=🔑"));
        assertEquals(1, byteLimited.capture(scope, "source", "password=abc").candidates().size());
    }

    @Test
    void expirationAndLifecycleDiscardInvalidateReferencesAndReleaseCapacity() {
        var clock = new MutableClock();
        var store = new SecretCandidateStore(clock, Duration.ofMinutes(30), 1, 100, 100);
        String old =
                store.capture(scope, "source", "password=alpha")
                        .candidates()
                        .getFirst()
                        .reference();
        clock.current = Instant.EPOCH.plus(Duration.ofMinutes(30));
        assertEquals(
                SecretCandidateStore.State.EXPIRED,
                store.describe(scope, old).orElseThrow().state());
        String next =
                store.capture(scope, "source", "password=alpha")
                        .candidates()
                        .getFirst()
                        .reference();
        assertNotEquals(old, next);
        store.discardSession("bob", "session");
        assertEquals(
                SecretCandidateStore.State.AVAILABLE,
                store.describe(scope, next).orElseThrow().state());
        store.discardSession("alice", "session");
        assertEquals(
                SecretCandidateStore.State.DISCARDED,
                store.describe(scope, next).orElseThrow().state());
        String last =
                store.capture(scope, "source", "password=beta").candidates().getFirst().reference();
        store.discardOwner("alice");
        assertEquals(
                SecretCandidateStore.State.DISCARDED,
                store.describe(scope, last).orElseThrow().state());
        assertTrue(new SecretCandidateStore().describe(scope, last).isEmpty());
    }

    @Test
    void scopeAndBindingFailuresNeverReachTheWriter() {
        var store = new SecretCandidateStore();
        String reference =
                store.capture(scope, "source", "password=synthetic-token")
                        .candidates()
                        .getFirst()
                        .reference();
        var writer = new InMemoryWriter();
        for (var other :
                List.of(
                        new SecretCandidateStore.Scope("bob", "session", "agent"),
                        new SecretCandidateStore.Scope("alice", "other", "agent"),
                        new SecretCandidateStore.Scope("alice", "session", "mate"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(other, reference, "github", "Repository", writer));
        }
        assertThrows(
                IllegalStateException.class,
                () -> store.importOnce(scope, "s_forged", "github", "Repository", writer));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.importOnce(scope, reference, "other-service", "Repository", writer));
        for (String label : List.of("", " ", " trailing ", "x".repeat(81))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.importOnce(scope, reference, "github", label, writer));
        }
        assertEquals(0, writer.unlockChecks);
        assertTrue(writer.attempts.isEmpty());
    }

    @Test
    void closedOwnersRetiredSessionsAndDiscardedAgentsCannotImport() {
        for (String lifecycle : List.of("owner", "session", "agent", "expiration")) {
            var clock = new MutableClock();
            var store = new SecretCandidateStore(clock, Duration.ofMinutes(30), 1, 100, 100);
            String reference =
                    store.capture(scope, "source", "password=synthetic-token")
                            .candidates()
                            .getFirst()
                            .reference();
            switch (lifecycle) {
                case "owner" -> store.closeOwner(scope.owner());
                case "session" -> store.retireSession(scope.owner(), scope.session());
                case "agent" -> store.discardAgent(scope);
                case "expiration" -> clock.current = Instant.EPOCH.plus(Duration.ofMinutes(30));
                default -> throw new AssertionError(lifecycle);
            }
            var writer = new InMemoryWriter();
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(scope, reference, "github", "Repository", writer));
            assertEquals(0, writer.unlockChecks);
            assertTrue(writer.attempts.isEmpty());
            assertThrows(
                    IllegalStateException.class,
                    () -> store.referenceSegments(scope, "[SECRET_REF:" + reference + "]"));
        }
    }

    private record StoredCredential(
            @NonNull String owner,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull String value) {}

    private static final class InMemoryWriter implements CredentialWriter {
        private boolean unlocked = true;
        private boolean failNextWrite;
        private int unlockChecks;
        private final @NonNull List<StoredCredential> attempts = new ArrayList<>();

        @Override
        public boolean isUnlocked(@NonNull String owner) {
            unlockChecks++;
            return unlocked && owner.equals("alice");
        }

        @Override
        public @NonNull String createImportedCredential(
                @NonNull String owner,
                @NonNull String reference,
                @NonNull String service,
                @NonNull String label,
                @NonNull String value) {
            attempts.add(new StoredCredential(owner, reference, service, label, value));
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException(value);
            }
            return "cred_01234567-89ab-cdef-0123-456789abcdef";
        }
    }

    private static final class MutableClock extends Clock {
        private @NonNull Instant current = Instant.EPOCH;

        @Override
        public @NonNull ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public @NonNull Clock withZone(@NonNull ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public @NonNull Instant instant() {
            return current;
        }
    }
}
