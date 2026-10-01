package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;

class AuthLifecycleManagerTest {
    @Test
    void failedAuthenticationPublishesNeitherSuccessEvent() {
        var vault = mock(KeysteadVault.class);
        var prompts = mock(PromptHandler.class);
        var events = mock(PluginLifecycleEvents.class);
        var lifecycle = new AuthLifecycleManager(vault, prompts);
        lifecycle.attachLifecycleEvents(events);
        doThrow(new IllegalArgumentException("Signup failed"))
                .when(vault)
                .signup("alice", "invalid");
        doThrow(new IllegalArgumentException("Login failed")).when(vault).login("alice", "invalid");
        assertThrows(IllegalArgumentException.class, () -> lifecycle.signup("alice", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.login("alice", "invalid"));
        verifyNoInteractions(events);
    }

    @Test
    void logoutNotificationPrecedesTerminalDetachAndVaultClose() {
        var vault = mock(KeysteadVault.class);
        var prompts = mock(PromptHandler.class);
        var events = mock(PluginLifecycleEvents.class);
        var lifecycle = new AuthLifecycleManager(vault, prompts);
        lifecycle.attachLifecycleEvents(events);
        lifecycle.logout("alice");
        var ordered = inOrder(events, prompts, vault);
        ordered.verify(events).userLogout("alice");
        ordered.verify(prompts).deactivateUser("alice");
        ordered.verify(vault).logout("alice");
    }

    @Test
    void signupAndLoginReportDifferentAuthenticationEvents() {
        KeysteadVault vault = mock(KeysteadVault.class);
        PromptHandler prompts = mock(PromptHandler.class);
        PluginLifecycleEvents events = mock(PluginLifecycleEvents.class);
        var lifecycle = new AuthLifecycleManager(vault, prompts);
        lifecycle.attachLifecycleEvents(events);

        lifecycle.signup("alice", "password");
        verify(events).userRegistered("alice");
        verify(events, never()).userLoggedIn("alice");

        lifecycle.login("alice", "password");
        verify(events).userLoggedIn("alice");
    }

    @Test
    void logoutClosesCaptureEvenWhenDetachAndVaultCloseFail() throws Exception {
        KeysteadVault vault = mock(KeysteadVault.class);
        PromptHandler prompts = mock(PromptHandler.class);
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope("alice", "session", "agent");
            var other = new Scope.AgentScope("bob", "session", "agent");
            String reference = capture(plugins, scope, "password=alpha");
            String otherReference = capture(plugins, other, "password=beta");
            var lifecycle = new AuthLifecycleManager(vault, prompts);
            lifecycle.attachLifecycleEvents(new PluginLifecycleEvents(plugins));
            doThrow(new IllegalStateException("Detach failed"))
                    .when(prompts)
                    .deactivateUser("alice");
            doThrow(new IllegalStateException("Close failed")).when(vault).logout("alice");
            assertThrows(IllegalStateException.class, () -> lifecycle.logout("alice"));
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            assertThrows(
                    IllegalStateException.class, () -> capture(plugins, scope, "password=late"));
            assertEquals(
                    "beta", PluginTestSupport.reveal(plugins, other, otherReference).orElseThrow());
        }
    }

    @Test
    void onlySuccessfulLoginReopensCaptureWithoutRestoringOldReferences() throws Exception {
        KeysteadVault vault = mock(KeysteadVault.class);
        PromptHandler prompts = mock(PromptHandler.class);
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope("alice", "session", "agent");
            String old = capture(plugins, scope, "password=alpha");
            var lifecycle = new AuthLifecycleManager(vault, prompts);
            lifecycle.attachLifecycleEvents(new PluginLifecycleEvents(plugins));
            lifecycle.logout("alice");
            doThrow(new IllegalArgumentException("Login failed"))
                    .when(vault)
                    .login("alice", "invalid");
            assertThrows(IllegalArgumentException.class, () -> lifecycle.login("alice", "invalid"));
            assertThrows(
                    IllegalStateException.class, () -> capture(plugins, scope, "password=alpha"));
            lifecycle.login("alice", "test-password");
            String reopened = capture(plugins, scope, "password=alpha");
            assertNotEquals(old, reopened);
            assertTrue(PluginTestSupport.reveal(plugins, scope, old).isEmpty());
        }
    }

    private static @NonNull String capture(
            @NonNull PluginManager plugins, Scope.@NonNull AgentScope scope, @NonNull String text)
            throws PluginFailure {
        String captured =
                PluginTestSupport.protect(
                        plugins, BeforeTextCommitEvent.Phase.INPUT, scope, "source", text);
        var matcher = Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }
}
