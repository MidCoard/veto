package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.plugin.runtime.PluginHostServices;
import top.focess.veto.plugin.runtime.PluginManager;
import top.focess.veto.plugin.runtime.PluginTestSupport;
import top.focess.veto.plugin.secrets.SecretProtectionConfiguration;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

class CredentialImportIntegrationTest {
    private static final @NonNull String IMPORT_TOOL =
            "plugin_top_focess_secret_protection__import_detected_credential";

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void screenedImportBindsCallerAndArgumentsBeforeStorage(@TempDir @NonNull Path directory)
            throws Exception {
        var session = UUID.randomUUID();
        var user = UUID.randomUUID();
        @NonNull KeysteadVault vault = mock();
        when(vault.isUnlocked("alice")).thenReturn(true);
        var mapper = new ObjectMapper();
        var workspace = Workspace.single(directory, PathMode.REAL);
        try (var plugins = PluginTestSupport.manager(hostServices(vault))) {
            var scope = new TextProtection.Scope("alice", session.toString(), "agent");
            String reference = reference(plugins, scope);
            when(vault.createImportedCredential(
                            "alice", reference, "github", "Repository", "synthetic-token"))
                    .thenReturn("cred_test");
            var engine = engineWith(mapper, plugins);
            var definition = importTool(engine);
            var gateway =
                    new Gateway(
                            workspace,
                            new DangerComputation(),
                            SlmScreeningProvider.unavailable(),
                            DeployerPolicy.FULL_ACCESS,
                            ProtectedSet.empty(),
                            new ReadHistory());
            var call =
                    new ToolCall(
                            IMPORT_TOOL,
                            Map.of(
                                    "secret_ref",
                                    reference,
                                    "service",
                                    "github",
                                    "label",
                                    "Repository"),
                            "call");
            var screened =
                    assertInstanceOf(
                            ToolDocs.nonNullClass(GatewayResult.Screened.class),
                            gateway.screen(call, definition));
            assertInstanceOf(
                    ToolDocs.nonNullClass(ApprovalDecision.Prompt.class),
                    new HitlRegistry().decide("agent", call, definition, screened));
            assertFalse(engine.execute(call, definition).success());
            verifyNoInteractions(vault);
            var permit =
                    gateway.revalidateExecution(call, definition, screened.executionPermit())
                            .withCaller("agent", user, null, "alice", session);
            ToolCallContextHolder.set(
                    new ToolCallContext(
                            "mate",
                            user,
                            null,
                            "alice",
                            session,
                            ToolResultPresentationMode.BASIC,
                            permit));
            assertFalse(engine.execute(call, definition).success());
            verifyNoInteractions(vault);
            ToolCallContextHolder.set(
                    new ToolCallContext(
                            "agent",
                            user,
                            null,
                            "alice",
                            session,
                            ToolResultPresentationMode.BASIC,
                            permit));
            var changed =
                    new ToolCall(
                            IMPORT_TOOL,
                            Map.of(
                                    "secret_ref",
                                    reference,
                                    "service",
                                    "github",
                                    "label",
                                    "Changed"),
                            "call");
            assertFalse(engine.execute(changed, definition).success());
            verifyNoInteractions(vault);
            var result = engine.execute(call, definition);
            assertTrue(result.success(), result.content());
            assertFalse(result.content().contains("synthetic-token"));
            assertEquals(
                    "cred_test", mapper.readTree(result.content()).path("credential_ref").asText());
            assertEquals("created", mapper.readTree(result.content()).path("status").asText());
            assertTrue(engine.execute(call, definition).success());
            verify(vault, times(1))
                    .createImportedCredential(
                            "alice", reference, "github", "Repository", "synthetic-token");
        }
    }

    private static @NonNull PluginHostServices hostServices(@NonNull KeysteadVault vault) {
        return new SecretProtectionConfiguration()
                .pluginHostServices(
                        PluginTestSupport.providerOf(vault), PluginTestSupport.providerOf(null));
    }

    private static @NonNull String reference(
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

    private static @NonNull ToolEngineImpl engineWith(
            @NonNull ObjectMapper mapper, @NonNull PluginManager plugins) {
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(PluginManager.class)).thenReturn(Map.of("plugins", plugins));
        var engine = new ToolEngineImpl(mapper, List.of(), context);
        engine.afterSingletonsInstantiated();
        return engine;
    }

    private static @NonNull ToolDefinition importTool(@NonNull ToolEngineImpl engine) {
        return engine.getActiveTools(null).stream()
                .filter(definition -> definition.name().equals(IMPORT_TOOL))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void importAccessChecksTheExactOperationAndScopeBeforeInvokingStorage(
            @TempDir @NonNull Path directory) throws Exception {
        var session = UUID.randomUUID();
        @NonNull KeysteadVault vault = mock();
        var services = hostServices(vault);
        var service = services.services().get(ToolDocs.nonNullClass(CredentialImportAccess.class));
        if (!(service instanceof CredentialImportAccess access))
            throw new AssertionError("Import host service missing");
        try (var plugins = PluginTestSupport.manager(services)) {
            var scope = new TextProtection.Scope("alice", session.toString(), "agent");
            String reference = reference(plugins, scope);
            var engine = engineWith(new ObjectMapper(), plugins);
            var definition = importTool(engine);
            var workspace = Workspace.single(directory, PathMode.REAL);
            var call =
                    new ToolCall(
                            IMPORT_TOOL,
                            Map.of(
                                    "secret_ref",
                                    reference,
                                    "service",
                                    "github",
                                    "label",
                                    "Repository"),
                            "call");
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize(reference, "github", "Repository"));

            installContext(call, definition, workspace, "alice", session, "agent");
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize(reference, "github", "Changed"));
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize(reference, "other-service", "Repository"));
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize("other-reference", "github", "Repository"));

            for (var incompatibleCall :
                    List.of(
                            // The tool-name binding is enforced by the engine's registration
                            // identity check; the host-side authorization pins the exact
                            // argument set instead (it no longer knows the plugin's tool name).
                            new ToolCall(
                                    call.toolName(),
                                    Map.of(
                                            "secret_ref",
                                            reference,
                                            "service",
                                            "github",
                                            "label",
                                            "Repository",
                                            "extra",
                                            "not approved"),
                                    call.callId()))) {
                installContext(incompatibleCall, definition, workspace, "alice", session, "agent");
                assertThrows(
                        SecurityException.class,
                        () -> access.authorize(reference, "github", "Repository"));
            }

            installContext(call, definition, workspace, null, session, "agent");
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize(reference, "github", "Repository"));
            installContext(call, definition, workspace, "alice", null, "agent");
            assertThrows(
                    SecurityException.class,
                    () -> access.authorize(reference, "github", "Repository"));
            for (var other :
                    List.of(
                            new TextProtection.Scope("bob", session.toString(), "agent"),
                            new TextProtection.Scope(
                                    "alice", UUID.randomUUID().toString(), "agent"),
                            new TextProtection.Scope("alice", session.toString(), "mate"))) {
                installContext(
                        call,
                        definition,
                        workspace,
                        other.ownerId(),
                        UUID.fromString(other.sessionId()),
                        other.agentId());
                assertThrows(
                        IllegalStateException.class,
                        () -> invokeImport(plugins, reference, "github", "Repository"));
            }
            verifyNoInteractions(vault);
            assertEquals(
                    "synthetic-token",
                    PluginTestSupport.reveal(plugins, scope, reference).orElseThrow());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // The contributed handler is a CapabilityTool<?>.
    private static @NonNull String invokeImport(
            @NonNull PluginManager plugins,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label)
            throws Exception {
        var entry =
                plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS).stream()
                        .filter(
                                value ->
                                        value.implementation()
                                                .getName()
                                                .equals("import_detected_credential"))
                        .findFirst()
                        .orElseThrow();
        CapabilityTool<?> tool = entry.implementation();
        var mapper = new ObjectMapper();
        @NonNull Object args =
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

    private static void installContext(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Workspace workspace,
            String owner,
            UUID session,
            @NonNull String agent) {
        var user = UUID.randomUUID();
        var permit =
                ToolExecutionPermit.capture(call, definition, workspace)
                        .withCaller(agent, user, null, owner, session);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agent,
                        user,
                        null,
                        owner,
                        session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        ToolCallContextHolder.setCurrentCallId(call.callId());
    }

    @Test
    void rolesKeepImportWithinItsOwnBoundary() {
        assertTrue(
                RoleToolFilter.capabilitiesFor(Role.STANDALONE)
                        .contains(ToolCapability.PRIVILEGED));
        assertTrue(RoleToolFilter.capabilitiesFor(Role.MATE).contains(ToolCapability.PRIVILEGED));
        assertFalse(
                RoleToolFilter.capabilitiesFor(Role.LEADER).contains(ToolCapability.PRIVILEGED));
    }
}
