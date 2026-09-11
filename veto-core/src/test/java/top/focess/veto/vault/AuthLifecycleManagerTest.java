package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.command.PromptHandler;

class AuthLifecycleManagerTest {
    @Test
    void logoutClosesCaptureEvenWhenDetachAndVaultCloseFail() {
        @NonNull KeysteadVault vault = mock();
        @NonNull PromptHandler prompts = mock();
        var store = new SecretCandidateStore();
        var scope = new SecretCandidateStore.Scope("alice", "session", "agent");
        var other = new SecretCandidateStore.Scope("bob", "session", "agent");
        String reference =
                store.capture(scope, "source", "password=alpha")
                        .candidates()
                        .getFirst()
                        .reference();
        String otherReference =
                store.capture(other, "source", "password=beta").candidates().getFirst().reference();
        var lifecycle = new AuthLifecycleManager(vault, prompts);
        lifecycle.attachCandidates(store);
        doThrow(new IllegalStateException("Detach failed")).when(prompts).deactivateUser("alice");
        doThrow(new IllegalStateException("Close failed")).when(vault).logout("alice");
        assertThrows(IllegalStateException.class, () -> lifecycle.logout("alice"));
        assertEquals(
                SecretCandidateStore.State.DISCARDED,
                store.describe(scope, reference).orElseThrow().state());
        assertThrows(
                IllegalStateException.class, () -> store.capture(scope, "late", "password=late"));
        assertEquals(
                SecretCandidateStore.State.AVAILABLE,
                store.describe(other, otherReference).orElseThrow().state());
    }

    @Test
    void onlySuccessfulLoginReopensCaptureWithoutRestoringOldReferences() {
        @NonNull KeysteadVault vault = mock();
        @NonNull PromptHandler prompts = mock();
        var store = new SecretCandidateStore();
        var scope = new SecretCandidateStore.Scope("alice", "session", "agent");
        String old =
                store.capture(scope, "source", "password=alpha")
                        .candidates()
                        .getFirst()
                        .reference();
        var lifecycle = new AuthLifecycleManager(vault, prompts);
        lifecycle.attachCandidates(store);
        lifecycle.logout("alice");
        doThrow(new IllegalArgumentException("Login failed")).when(vault).login("alice", "invalid");
        assertThrows(IllegalArgumentException.class, () -> lifecycle.login("alice", "invalid"));
        assertThrows(
                IllegalStateException.class, () -> store.capture(scope, "late", "password=alpha"));
        lifecycle.login("alice", "test-password");
        assertNotEquals(
                old,
                store.capture(scope, "new", "password=alpha").candidates().getFirst().reference());
        assertEquals(
                SecretCandidateStore.State.DISCARDED,
                store.describe(scope, old).orElseThrow().state());
    }
}
