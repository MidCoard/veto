package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.plugin.contract.PluginFailure;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.contract.TextProtection;
import top.focess.veto.plugin.api.PluginState;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.CredentialWriter;

/**
 * Host-side plugin integration: ServiceLoader discovery, host-service delivery through {@code
 * PluginContext}, session-lifecycle dispatch, and the session-less {@code
 * veto:observation-middleware} floor. The real secret-protection plugin is discovered from the test
 * runtime classpath.
 */
class PluginManagerDiscoveryTest {
    @Test
    void serviceLoaderDiscoversTheSecretProtectionPlugin() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var plugin = plugins.plugin("top.focess.secret-protection");
            assertEquals(PluginState.ACTIVE, plugin.state());
            assertFalse(
                    plugins.catalog().entries(StandardContributionPoints.INPUT_PROTECTION).isEmpty());
            assertFalse(plugins.catalog().entries(StandardContributionPoints.OBSERVATION).isEmpty());
            assertFalse(
                    plugins.catalog().entries(StandardContributionPoints.SESSION_LIFECYCLE).isEmpty());
            assertFalse(plugins.catalog().entries(StandardContributionPoints.TOOLS).isEmpty());
        }
    }

    @Test
    void importToolFailsWithoutHostGrantedAccess() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new TextProtection.Scope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> invokeImport(plugins, reference, "github", "Repository"));
            assertEquals("Import host is unavailable", failure.getMessage());
        }
    }

    @Test
    void importToolUsesTheHostGrantedAccess() throws Exception {
        CredentialWriter writer =
                new CredentialWriter() {
                    @Override
                    public boolean isUnlocked(@NonNull String owner) {
                        return true;
                    }

                    @Override
                    public @NonNull String createImportedCredential(
                            @NonNull String owner,
                            @NonNull String reference,
                            @NonNull String service,
                            @NonNull String label,
                            @NonNull String value) {
                        assertEquals("synthetic-token", value);
                        return "cred_test";
                    }
                };
        CredentialImportAccess access =
                (reference, service, label) ->
                        new CredentialImportAccess.Authorization(
                                "owner", "session", "agent", writer);
        try (var plugins =
                PluginTestSupport.manager(
                        new PluginHostServices(
                                Map.of(
                                        ToolDocs.nonNullClass(CredentialImportAccess.class),
                                        access)))) {
            var scope = new TextProtection.Scope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            var receipt = invokeImport(plugins, reference, "github", "Repository");
            assertTrue(
                    receipt instanceof JsonValue.ObjectValue object
                            && object.values().get("credential_ref")
                                    instanceof JsonValue.StringValue ref
                            && ref.value().equals("cred_test"),
                    String.valueOf(receipt));
        }
    }

    @Test
    void lifecycleEventsReachThePluginThroughTheDispatcher() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var events = new PluginLifecycleEvents(plugins);
            var scope = new TextProtection.Scope("owner", "session", "agent");
            String reference = capture(plugins, scope);
            events.agentTerminated("owner", "session", "agent");
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            reference = capture(plugins, scope);
            events.sessionClosed("owner", "session");
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
            assertThrows(IllegalStateException.class, () -> capture(plugins, scope));
            var otherSession = new TextProtection.Scope("owner", "other-session", "agent");
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
            @NonNull PluginManager plugins, TextProtection.@NonNull Scope scope)
            throws PluginFailure {
        String captured =
                PluginTestSupport.protect(
                        plugins,
                        StandardContributionPoints.INPUT_PROTECTION,
                        scope,
                        "source",
                        "password=synthetic-token");
        var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }

    private static @NonNull JsonValue invokeImport(
            @NonNull PluginManager plugins,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label)
            throws Exception {
        var entry = plugins.catalog().entries(StandardContributionPoints.TOOLS).getFirst();
        var arguments =
                PluginJson.object(
                        new ObjectMapper()
                                .valueToTree(
                                        Map.of(
                                                "secret_ref", reference,
                                                "service", service,
                                                "label", label)));
        return plugins.plugin(entry.source().namespace())
                .execute(() -> entry.implementation().handler().invoke(arguments, () -> false));
    }
}
