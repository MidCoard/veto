package top.focess.veto.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.InputProtection;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.CredentialWriter;

/** The plugin is self-contained: ServiceLoader discovery, typed points, and host-service import. */
@SuppressWarnings("nullness") // Cross-module class literals read as nullable.
class SecretProtectionPluginTest {
    private static final TextProtection.@NonNull Scope SCOPE =
            new TextProtection.Scope("owner", "session", "agent");

    private static @NonNull List<Contribution<?>> initialize(
            @NonNull SecretProtectionPlugin plugin,
            @NonNull Map<@NonNull Class<?>, @NonNull Object> services)
            throws PluginFailure {
        return plugin.initialize(
                        new PluginContext(plugin.identity(), services),
                        new JsonValue.ObjectValue(Map.of()))
                .entries();
    }

    @Test
    void serviceLoaderDiscoversThePlugin() {
        var discovered =
                ServiceLoader.load(VetoPlugin.class).stream()
                        .map(ServiceLoader.Provider::type)
                        .collect(Collectors.toSet());
        assertTrue(discovered.contains(SecretProtectionPlugin.class));
    }

    @Test
    void contributesExactlyTheTypedStandardPoints() throws Exception {
        var entries = initialize(new SecretProtectionPlugin(), Map.of());
        var byPoint =
                entries.stream()
                        .collect(
                                Collectors.toMap(
                                        entry -> entry.point().id().value(),
                                        Contribution::implementation));
        assertEquals(
                java.util.Set.of(
                        "veto:frontend",
                        "veto:input-protection",
                        "veto:file-observation",
                        "veto:file-protection",
                        "veto:observation-middleware",
                        "veto:session-lifecycle",
                        "veto:native-tools"),
                byPoint.keySet());
        assertInstanceOf(InputProtection.class, byPoint.get("veto:input-protection"));
        assertInstanceOf(ObservationMiddleware.class, byPoint.get("veto:observation-middleware"));
        assertInstanceOf(SessionLifecycle.class, byPoint.get("veto:session-lifecycle"));
        assertInstanceOf(CapabilityTool.class, byPoint.get("veto:native-tools"));
    }

    @Test
    void sessionLifecycleDrivesCaptureAvailability() throws Exception {
        var entries = initialize(new SecretProtectionPlugin(), Map.of());
        var input = contribution(entries, InputProtection.class);
        var lifecycle = contribution(entries, SessionLifecycle.class);
        String captured = input.transform(SCOPE, "user", "password=alpha");
        assertTrue(captured.contains("[SECRET_REF:s_"), captured);
        lifecycle.onOwnerClosed("owner");
        assertThrows(
                IllegalStateException.class,
                () -> input.transform(SCOPE, "user", "password=alpha"));
        lifecycle.onOwnerOpen("owner");
        assertTrue(input.transform(SCOPE, "user", "password=beta").contains("[SECRET_REF:s_"));
        lifecycle.onSessionClosed("owner", "session");
        assertThrows(
                IllegalStateException.class, () -> input.transform(SCOPE, "user", "password=beta"));
    }

    @Test
    void observationMiddlewareMasksWithoutSessionScope() throws Exception {
        var entries = initialize(new SecretProtectionPlugin(), Map.of());
        var mask = contribution(entries, ObservationMiddleware.class);
        String masked = mask.transform("exfiltrating api_key=ABCD", () -> false);
        assertTrue(masked.contains("[REDACTED_"), masked);
        assertEquals("ordinary text", mask.transform("ordinary text", () -> false));
    }

    @Test
    void observationMiddlewareAlsoMasksSensitiveDataClasses() throws Exception {
        var entries = initialize(new SecretProtectionPlugin(), Map.of());
        var mask = contribution(entries, ObservationMiddleware.class);
        String masked = mask.transform("IP 10.0.0.50, mail admin@internal.corp", () -> false);
        assertTrue(masked.contains("[REDACTED_IP]"), masked);
        assertTrue(masked.contains("[REDACTED_EMAIL]"), masked);
        assertFalse(masked.contains("10.0.0.50"));
        assertFalse(masked.contains("admin@internal.corp"));
    }

    @Test
    void importToolRequiresTheHostGrantedService() throws Exception {
        var entries = initialize(new SecretProtectionPlugin(), Map.of());
        var input = contribution(entries, InputProtection.class);
        String reference = reference(input.transform(SCOPE, "user", "password=synthetic-token"));
        var tool = contribution(entries, CapabilityTool.class);
        var failure =
                assertThrows(
                        IllegalStateException.class, () -> invoke(tool, reference, "Repository"));
        assertEquals("Import host is unavailable", failure.getMessage());
    }

    @Test
    void importToolUsesTheHostGrantedService() throws Exception {
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
                        return "cred_test";
                    }
                };
        CredentialImportAccess access =
                (reference, service, label) ->
                        new CredentialImportAccess.Authorization(
                                "owner", "session", "agent", writer);
        var entries =
                initialize(
                        new SecretProtectionPlugin(), Map.of(CredentialImportAccess.class, access));
        var input = contribution(entries, InputProtection.class);
        String reference = reference(input.transform(SCOPE, "user", "password=synthetic-token"));
        String receipt =
                invoke(contribution(entries, CapabilityTool.class), reference, "Repository");
        assertTrue(receipt.contains("\"credential_ref\":\"cred_test\""), receipt);
        assertTrue(receipt.contains("\"status\":\"created\""), receipt);
    }

    private static <T extends @NonNull Object> @NonNull T contribution(
            @NonNull List<Contribution<?>> entries, @NonNull Class<T> type) {
        for (var entry : entries)
            if (type.isInstance(entry.implementation())) return type.cast(entry.implementation());
        throw new AssertionError("Contribution missing: " + type.getSimpleName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // The contributed handler is a CapabilityTool<?>.
    private static @NonNull String invoke(
            @NonNull CapabilityTool<?> tool, @NonNull String reference, @NonNull String label)
            throws Exception {
        return ((CapabilityTool) tool)
                .execute(
                        new SecretProtectionPlugin.ImportCredentialArgs(
                                reference, "github", label));
    }

    private static @NonNull String reference(@NonNull String captured) {
        var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }
}
