package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.FrontendContribution;
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
            assertTrue(plugins.plugins().isEmpty());
        }
    }

    @Test
    void manifestDiscoversTheSecretProtectionPlugin() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var plugin = plugins.plugin("top.focess.secret-protection");
            assertSame(plugin, plugins.plugin("org.veto.secret-protection"));
            assertEquals(PluginState.ACTIVE, plugin.state());
            assertFalse(
                    plugins.catalog().entries(StandardContributionPoints.OBSERVATION).isEmpty());
            assertFalse(plugins.catalog().entries(StandardContributionPoints.LISTENERS).isEmpty());
            assertFalse(plugins.catalog().entries(StandardContributionPoints.TOOLS).isEmpty());
        }
    }

    @Test
    void importToolFailsWithoutHostGrantedAccess() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new FrontendContribution.ActionContext("owner", "session", "agent");
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
            var scope = new FrontendContribution.ActionContext("owner", "session", "agent");
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
            var events = new PluginLifecycleEvents(plugins);
            var scope = new FrontendContribution.ActionContext("owner", "session", "agent");
            String reference = capture(plugins, scope);
            events.agentTerminated("owner", "session", "agent");
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            reference = capture(plugins, scope);
            events.sessionClosed("owner", "session");
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            assertThrows(IllegalStateException.class, () -> capture(plugins, scope));
            var otherSession =
                    new FrontendContribution.ActionContext("owner", "other-session", "agent");
            capture(plugins, otherSession);
            events.ownerClosed("owner");
            assertThrows(IllegalStateException.class, () -> capture(plugins, otherSession));
            events.ownerOpened("owner");
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
            @NonNull PluginManager plugins, FrontendContribution.@NonNull ActionContext scope)
            throws PluginFailure {
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
                plugins.catalog().entries(StandardContributionPoints.TOOLS).stream()
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
