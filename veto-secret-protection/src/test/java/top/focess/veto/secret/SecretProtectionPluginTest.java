package top.focess.veto.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;

/** The plugin is self-contained: typed points and host-service import. */
class SecretProtectionPluginTest {
    private static final Scope.@NonNull AgentScope SCOPE =
            new Scope.AgentScope("owner", "session", "agent");

    private static @NonNull List<Contribution<?>> initialize(
            @NonNull Map<@NonNull Class<?>, @NonNull Object> services) {
        var identity = new PluginIdentity("top.focess.secret-protection", "1.0.100");
        var entries = new ArrayList<@NonNull Contribution<?>>();
        var context =
                new PluginContext(
                        identity,
                        () -> {},
                        () -> PluginState.NEW,
                        services,
                        Map.of(
                                StandardContributionPoints.FRONTEND, entries::add,
                                StandardContributionPoints.OBSERVATION, entries::add,
                                StandardContributionPoints.LISTENERS, entries::add,
                                StandardContributionPoints.TOOLS, entries::add));
        new SecretProtectionPlugin(context, new JsonValue.ObjectValue(Map.of()));
        return List.copyOf(entries);
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
        lifecycle.onUserLogout(new UserLogoutEvent(new Scope.UserScope("owner")));
        assertThrows(IllegalStateException.class, () -> commit(lifecycle, SCOPE, "password=alpha"));
        lifecycle.onUserAuthenticated(new UserLoggedInEvent(new Scope.UserScope("owner")));
        assertTrue(commit(lifecycle, SCOPE, "password=beta").contains("[SECRET_REF:s_"));
        lifecycle.onSessionDeleted(
                new SessionDeletedEvent(new Scope.SessionScope("owner", "session")));
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
        assertEquals("Vault access is unavailable", failure.getMessage());
    }

    @Test
    void importToolUsesTheHostGrantedVault() throws Exception {
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
                        return "cred_test";
                    }
                };
        VaultAccess access = arguments -> writer;
        var entries = initialize(Map.of(VaultAccess.class, access));
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
            Scope.@NonNull AgentScope scope,
            @NonNull String text) {
        var event =
                new BeforeTextCommitEvent(
                        scope.owner(),
                        scope.session(),
                        scope.agent(),
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
