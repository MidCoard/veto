package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.plugin.contract.PluginFailure;
import top.focess.veto.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.contract.TextProtection;
import top.focess.veto.plugin.runtime.PluginLifecycleEvents;
import top.focess.veto.plugin.runtime.PluginManager;
import top.focess.veto.plugin.runtime.PluginTestSupport;

class AuthLifecycleManagerTest {
    @Test
    void logoutClosesCaptureEvenWhenDetachAndVaultCloseFail() throws Exception {
        @NonNull KeysteadVault vault = mock();
        @NonNull PromptHandler prompts = mock();
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new TextProtection.Scope("alice", "session", "agent");
            var other = new TextProtection.Scope("bob", "session", "agent");
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
        @NonNull KeysteadVault vault = mock();
        @NonNull PromptHandler prompts = mock();
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new TextProtection.Scope("alice", "session", "agent");
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
            @NonNull PluginManager plugins,
            TextProtection.@NonNull Scope scope,
            @NonNull String text)
            throws PluginFailure {
        String captured =
                PluginTestSupport.protect(
                        plugins,
                        StandardContributionPoints.INPUT_PROTECTION,
                        scope,
                        "source",
                        text);
        var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }
}
