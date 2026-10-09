package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.vault.TestUsers.ALICE;
import static top.focess.veto.vault.TestUsers.BOB;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.terminal.IpcServer;

class AuthLifecycleManagerTest {
    private static @NonNull ObjectProvider<IpcServer> ipcServers() {
        return new StaticListableBeanFactory().getBeanProvider(IpcServer.class);
    }

    private static @NonNull UserEntity alice() {
        var account = mock(UserEntity.class);
        when(account.getUserId()).thenReturn(ALICE);
        when(account.getUsername()).thenReturn("alice");
        return account;
    }

    private static @NonNull KeysteadVault vault() {
        var vault = mock(KeysteadVault.class);

        when(vault.login("alice", "password")).thenReturn(ALICE);
        when(vault.login("alice", "test-password")).thenReturn(ALICE);
        when(vault.login("alice", "replacement-password")).thenReturn(ALICE);
        return vault;
    }

    @Test
    void ownerLogoutRevokesOldTokensBeforeTheUsernameCanBeReused() {
        var sessions = new LoginSessionManager();
        var oldToken = sessions.createLoginSession(ALICE, "alice");
        var otherToken = sessions.createLoginSession(BOB, "bob");
        var vault = vault();
        var lifecycle =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        sessions,
                        ipcServers());
        lifecycle.logout(ALICE);
        lifecycle.login(alice(), "replacement-password");
        var replacementId = UUID.randomUUID();
        var replacementToken = sessions.createLoginSession(replacementId, "alice");
        assertTrue(sessions.validateToken(oldToken).isEmpty());
        assertEquals(
                replacementId, sessions.validateToken(replacementToken).orElseThrow().userId());
        assertTrue(sessions.validateToken(otherToken).isPresent());
    }

    @Test
    void failedAuthenticationPublishesNeitherSuccessEvent() {
        var vault = vault();
        var prompts = mock(PromptHandler.class);
        var events = mock(EventManager.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault, prompts, events, new LoginSessionManager(), ipcServers());
        doThrow(new IllegalArgumentException("Signup failed"))
                .when(vault)
                .login("alice", "invalid");
        doThrow(new IllegalArgumentException("Login failed")).when(vault).login("alice", "invalid");
        assertThrows(IllegalArgumentException.class, () -> lifecycle.signup(alice(), "invalid"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.login(alice(), "invalid"));
        verifyNoInteractions(events);
    }

    @Test
    void logoutNotificationPrecedesTerminalDetachAndVaultClose() {
        var vault = vault();
        var prompts = mock(PromptHandler.class);
        var events = mock(EventManager.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault, prompts, events, new LoginSessionManager(), ipcServers());
        lifecycle.logout(ALICE);
        var ordered = inOrder(events, prompts, vault);
        ordered.verify(events)
                .submit(
                        argThat(
                                event ->
                                        event instanceof UserLogoutEvent fact
                                                && fact.scope()
                                                        .equals(new Scope.UserScope(ALICE))));
        ordered.verify(prompts).deactivateUser(ALICE);
        ordered.verify(vault).logout(ALICE);
    }

    @Test
    void signupAndLoginReportDifferentAuthenticationEvents() {
        KeysteadVault vault = vault();
        PromptHandler prompts = mock(PromptHandler.class);
        EventManager events = mock(EventManager.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault, prompts, events, new LoginSessionManager(), ipcServers());

        lifecycle.signup(alice(), "password");
        verify(events)
                .submit(
                        argThat(
                                event ->
                                        event instanceof UserRegisteredEvent fact
                                                && fact.scope()
                                                        .equals(new Scope.UserScope(ALICE))));
        verify(events, never())
                .submit(
                        argThat(
                                event ->
                                        event instanceof UserLoggedInEvent fact
                                                && fact.scope()
                                                        .equals(new Scope.UserScope(ALICE))));

        lifecycle.login(alice(), "password");
        verify(events)
                .submit(
                        argThat(
                                event ->
                                        event instanceof UserLoggedInEvent fact
                                                && fact.scope()
                                                        .equals(new Scope.UserScope(ALICE))));
    }

    @Test
    void logoutClosesCaptureEvenWhenDetachAndVaultCloseFail() throws Exception {
        KeysteadVault vault = vault();
        PromptHandler prompts = mock(PromptHandler.class);
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope(ALICE, "session", "agent");
            var other = new Scope.AgentScope(BOB, "session", "agent");
            String reference = capture(plugins, scope, "password=alpha");
            String otherReference = capture(plugins, other, "password=beta");
            var lifecycle =
                    new AuthLifecycleManager(
                            vault,
                            prompts,
                            PluginTestSupport.eventManager(plugins),
                            new LoginSessionManager(),
                            ipcServers());
            doThrow(new IllegalStateException("Detach failed")).when(prompts).deactivateUser(ALICE);
            doThrow(new IllegalStateException("Close failed")).when(vault).logout(ALICE);
            assertThrows(IllegalStateException.class, () -> lifecycle.logout(ALICE));
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            assertEquals(
                    "password=late",
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "source",
                            "password=late"));
            assertEquals(
                    "beta", PluginTestSupport.reveal(plugins, other, otherReference).orElseThrow());
        }
    }

    @Test
    void onlySuccessfulLoginReopensCaptureWithoutRestoringOldReferences() throws Exception {
        KeysteadVault vault = vault();
        PromptHandler prompts = mock(PromptHandler.class);
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope(ALICE, "session", "agent");
            String old = capture(plugins, scope, "password=alpha");
            var lifecycle =
                    new AuthLifecycleManager(
                            vault,
                            prompts,
                            PluginTestSupport.eventManager(plugins),
                            new LoginSessionManager(),
                            ipcServers());
            lifecycle.logout(ALICE);
            doThrow(new IllegalArgumentException("Login failed"))
                    .when(vault)
                    .login("alice", "invalid");
            assertThrows(IllegalArgumentException.class, () -> lifecycle.login(alice(), "invalid"));
            assertEquals(
                    "password=alpha",
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "source",
                            "password=alpha"));
            assertTrue(PluginTestSupport.reveal(plugins, scope, old).isEmpty());
            lifecycle.login(alice(), "test-password");
            String reopened = capture(plugins, scope, "password=alpha");
            assertNotEquals(old, reopened);
            assertTrue(PluginTestSupport.reveal(plugins, scope, old).isEmpty());
            assertEquals("alpha", PluginTestSupport.reveal(plugins, scope, reopened).orElseThrow());
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
