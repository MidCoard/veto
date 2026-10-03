package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.util.Nullness;

/**
 * Host-side plugin integration: manifest discovery, host-service delivery through {@code
 * PluginContext}, lifecycle-event dispatch, and the session-less {@code
 * veto:observation-middleware} floor. The real secret-protection plugin is installed from its
 * packaged directory.
 */
class PluginManagerDiscoveryTest {
    @Test
    void classpathDoesNotInstallPluginsWithoutManifests() throws Exception {
        try (var plugins =
                new PluginManager(
                        "",
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations())) {
            assertTrue(plugins.registry().plugins().isEmpty());
        }
    }

    @Test
    void manifestDiscoversTheSecretProtectionPlugin() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var plugin = plugins.registry().plugin("top.focess.secret-protection");
            assertSame(plugin, plugins.registry().plugin("org.veto.secret-protection"));
            assertEquals(PluginState.ACTIVE, plugin.state());
            assertFalse(
                    plugins.registry().entries(StandardContributionPoints.OBSERVATION).isEmpty());
            assertFalse(plugins.registry().entries(StandardContributionPoints.LISTENERS).isEmpty());
            assertFalse(plugins.registry().entries(StandardContributionPoints.TOOLS).isEmpty());
        }
    }

    @Test
    void importToolFailsWithoutHostGrantedAccess() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> invokeImport(plugins, reference, "github", "Repository"));
            assertEquals("Vault access is unavailable", failure.getMessage());
        }
    }

    @Test
    void importToolUsesTheHostGrantedAccess() throws Exception {
        VaultAccess.Handle writer =
                new VaultAccess.Handle() {
                    @Override
                    public Scope.@NonNull AgentScope scope() {
                        return new Scope.AgentScope("owner", "session", "agent");
                    }

                    @Override
                    public boolean isUnlocked() {
                        return true;
                    }

                    @Override
                    public @NonNull String createSecureNote(
                            @NonNull String title,
                            @NonNull Map<@NonNull String, @NonNull String> attributes,
                            @NonNull String value) {
                        assertEquals("synthetic-token", value);
                        return "cred_test";
                    }
                };
        VaultAccess access = arguments -> writer;
        try (var plugins =
                PluginTestSupport.manager(
                        new PluginHostServices(Map.of(VaultAccess.class, access)))) {
            var scope = new Scope.AgentScope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            String receipt = invokeImport(plugins, reference, "github", "Repository");
            assertEquals(
                    "cred_test",
                    new ObjectMapper().readTree(receipt).path("credential_ref").asText());
        }
    }

    @Test
    void lifecycleEventsReachThePluginThroughTheDispatcher() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var events = PluginTestSupport.eventManager(plugins);
            var scope = new Scope.AgentScope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            events.submit(new AgentTerminatedEvent(scope));
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            reference = capture(plugins, scope);
            events.submit(new SessionDeletedEvent(new Scope.SessionScope("owner", "session")));
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            assertEquals(
                    "password=synthetic-token",
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "source",
                            "password=synthetic-token"));
            var otherSession = new Scope.AgentScope("owner", "other-session", "agent");
            String otherReference = capture(plugins, otherSession);
            events.submit(new UserLogoutEvent(new Scope.UserScope("owner")));
            assertTrue(PluginTestSupport.reveal(plugins, otherSession, otherReference).isEmpty());
            assertEquals(
                    "password=synthetic-token",
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            otherSession,
                            "source",
                            "password=synthetic-token"));
            events.submit(new UserLoggedInEvent(new Scope.UserScope("owner")));
            capture(plugins, otherSession);
        }
    }

    @Test
    void textMaskFloorMasksSecretsAndLeavesOrdinaryText() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            String masked = plugins.applyObservationMiddleware("exfiltrating api_key=ABCD");
            assertTrue(masked.contains("[REDACTED_"), masked);
            assertEquals("ordinary text", plugins.applyObservationMiddleware("ordinary text"));
        }
    }

    private static @NonNull String capture(
            @NonNull PluginManager plugins, Scope.@NonNull AgentScope scope) throws PluginFailure {
        String captured =
                PluginTestSupport.protect(
                        plugins,
                        BeforeTextCommitEvent.Phase.INPUT,
                        scope,
                        "source",
                        "password=synthetic-token");
        var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // The contributed handler is a CapabilityTool<?>.
    private static @NonNull String invokeImport(
            @NonNull PluginManager plugins,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label)
            throws Exception {
        var entry =
                plugins.registry().entries(StandardContributionPoints.TOOLS).stream()
                        .filter(
                                value ->
                                        value.implementation() instanceof CapabilityTool<?> tool
                                                && tool.getName()
                                                        .equals("import_detected_credential"))
                        .findFirst()
                        .orElseThrow();
        CapabilityTool<?> tool = (CapabilityTool<?>) entry.implementation();
        var mapper = new ObjectMapper();
        Object args =
                Nullness.requireNonNull(
                        mapper.treeToValue(
                                mapper.valueToTree(
                                        Map.of(
                                                "secret_ref", reference,
                                                "service", service,
                                                "label", label)),
                                tool.getArgsClass()));
        return ((CapabilityTool) tool).execute(args);
    }
}
