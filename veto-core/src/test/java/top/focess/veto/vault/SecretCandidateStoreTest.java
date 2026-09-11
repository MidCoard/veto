package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

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
        @NonNull KeysteadVault vault = mock();
        when(vault.isUnlocked("alice")).thenReturn(true);
        when(vault.createImportedCredential(
                        "alice", reference, "github", "Repository", "synthetic-token"))
                .thenThrow(new IllegalStateException("synthetic-token"))
                .thenReturn("cred_01234567-89ab-cdef-0123-456789abcdef");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.importOnce(scope, reference, "github", "Repository", vault));
        assertEquals("Credential import could not be saved", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(
                SecretCandidateStore.State.AVAILABLE,
                store.describe(scope, reference).orElseThrow().state());
        var imported = store.importOnce(scope, reference, "github", "Repository", vault);
        assertEquals(
                SecretCandidateStore.State.IMPORTED,
                store.describe(scope, reference).orElseThrow().state());
        assertEquals(imported, store.importOnce(scope, reference, "github", "Repository", vault));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.importOnce(scope, reference, "github", "Changed", vault));
        verify(vault, times(2))
                .createImportedCredential(
                        "alice", reference, "github", "Repository", "synthetic-token");
        when(vault.isUnlocked("alice")).thenReturn(false);
        assertThrows(
                IllegalStateException.class,
                () -> store.importOnce(scope, reference, "github", "Repository", vault));
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
        @NonNull Clock clock = mock();
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var store = new SecretCandidateStore(clock, Duration.ofMinutes(30), 1, 100, 100);
        String old =
                store.capture(scope, "source", "password=alpha")
                        .candidates()
                        .getFirst()
                        .reference();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofMinutes(30)));
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
}
