package top.focess.veto.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.CredentialImportAccess;
import top.focess.veto.api.credentials.CredentialWriter;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.OwnerClosedEvent;
import top.focess.veto.api.event.OwnerOpenEvent;
import top.focess.veto.api.event.SessionClosedEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contribution.Contribution;

/** The plugin is self-contained: typed points and host-service import. */
class SecretProtectionPluginTest {
    private static final FrontendContribution.@NonNull Scope SCOPE =
            new FrontendContribution.Scope("owner", "session", "agent");

    private static @NonNull List<Contribution<?>> initialize(
            @NonNull Map<@NonNull Class<?>, @NonNull Object> services) {
        var identity = new PluginIdentity("top.focess.secret-protection", "1.0.100");
        var context = new PluginContext(identity, () -> {}, () -> PluginState.NEW, services);
        return new SecretProtectionPlugin(context, new JsonValue.ObjectValue(Map.of()))
                .contributions()
                .entries();
    }

    @Test
    void contributesExactlyTheTypedStandardPoints() throws Exception {
        var entries = initialize(Map.of());
        var byPoint =
                entries.stream()
                        .collect(
                                Collectors.toMap(
                                        entry -> entry.point().id().value(),
                                        Contribution::implementation));
        assertEquals(
                Set.of(
                        "veto:frontend",
                        "veto:observation-middleware",
                        "veto:listeners",
                        "veto:tools"),
                byPoint.keySet());
        assertInstanceOf(ObservationMiddleware.class, byPoint.get("veto:observation-middleware"));
        assertInstanceOf(Listener.class, byPoint.get("veto:listeners"));
        assertInstanceOf(CapabilityTool.class, byPoint.get("veto:tools"));
    }

    @Test
    void lifecycleEventsDriveCaptureAvailability() throws Exception {
        var entries = initialize(Map.of());
        var lifecycle = contribution(entries, SecretProtectionPlugin.SecretLifecycle.class);
        String captured = commit(lifecycle, SCOPE, "password=alpha");
        assertTrue(captured.contains("[SECRET_REF:s_"), captured);
        lifecycle.onOwnerClosed(new OwnerClosedEvent("owner"));
        assertThrows(IllegalStateException.class, () -> commit(lifecycle, SCOPE, "password=alpha"));
        lifecycle.onOwnerOpen(new OwnerOpenEvent("owner"));
        assertTrue(commit(lifecycle, SCOPE, "password=beta").contains("[SECRET_REF:s_"));
        lifecycle.onSessionClosed(new SessionClosedEvent("owner", "session"));
        assertThrows(IllegalStateException.class, () -> commit(lifecycle, SCOPE, "password=beta"));
    }

    @Test
    void observationMiddlewareMasksWithoutSessionScope() throws Exception {
        var entries = initialize(Map.of());
        var mask = contribution(entries, ObservationMiddleware.class);
        String masked = mask.transform("exfiltrating api_key=ABCD", () -> false);
        assertTrue(masked.contains("[REDACTED_"), masked);
        assertEquals("ordinary text", mask.transform("ordinary text", () -> false));
    }

    @Test
    void observationMiddlewareAlsoMasksSensitiveDataClasses() throws Exception {
        var entries = initialize(Map.of());
        var mask = contribution(entries, ObservationMiddleware.class);
        String masked = mask.transform("IP 10.0.0.50, mail admin@internal.corp", () -> false);
        assertTrue(masked.contains("[REDACTED_IP]"), masked);
        assertTrue(masked.contains("[REDACTED_EMAIL]"), masked);
        assertFalse(masked.contains("10.0.0.50"));
        assertFalse(masked.contains("admin@internal.corp"));
    }

    @Test
    void importToolRequiresTheHostGrantedService() throws Exception {
        var entries = initialize(Map.of());
        var lifecycle = contribution(entries, SecretProtectionPlugin.SecretLifecycle.class);
        String reference = reference(commit(lifecycle, SCOPE, "password=synthetic-token"));
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
        var entries = initialize(Map.of(CredentialImportAccess.class, access));
        var lifecycle = contribution(entries, SecretProtectionPlugin.SecretLifecycle.class);
        String reference = reference(commit(lifecycle, SCOPE, "password=synthetic-token"));
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

    private static @NonNull String commit(
            SecretProtectionPlugin.@NonNull SecretLifecycle lifecycle,
            FrontendContribution.@NonNull Scope scope,
            @NonNull String text) {
        var event =
                new BeforeTextCommitEvent(
                        scope.ownerId(),
                        scope.sessionId(),
                        scope.agentId(),
                        () -> false,
                        BeforeTextCommitEvent.Phase.INPUT,
                        "user",
                        text);
        lifecycle.onTextCommit(event);
        return event.text();
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
        var matcher = Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }
}
