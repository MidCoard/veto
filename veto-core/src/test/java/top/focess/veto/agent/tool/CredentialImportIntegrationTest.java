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
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.integration.plugins.HostResourceConfiguration;
import top.focess.veto.integration.plugins.PluginHostServices;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
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
        KeysteadVault vault = mock(KeysteadVault.class);
        when(vault.isUnlocked("alice")).thenReturn(true);
        var mapper = new ObjectMapper();
        var workspace = Workspace.single(directory, PathMode.REAL);
        try (var plugins = PluginTestSupport.manager(hostServices(vault))) {
            var scope = new Scope.AgentScope("alice", session.toString(), "agent");
            String reference = reference(plugins, scope);
            when(vault.createSecureNoteIfAbsent(
                            "alice",
                            "veto.import." + reference,
                            Map.of(
                                    "veto.import.id", reference,
                                    "veto.import.service", "github",
                                    "veto.import.label", "Repository"),
                            "synthetic-token"))
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
                            GatewayResult.Screened.class, gateway.screen(call, definition));
            assertInstanceOf(
                    ApprovalDecision.Prompt.class,
                    new HitlRegistry().decide("agent", call, definition, screened));
            assertFalse(engine.execute(call, definition).success());
            verifyNoInteractions(vault);
            var permit =
                    gateway.revalidateExecution(call, definition, screened.executionPermit())
                            .withCaller("agent", user, "alice", session);
            ToolCallContextHolder.set(
                    new ToolCallContext(
                            "mate",
                            user,
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
                    .createSecureNoteIfAbsent(
                            "alice",
                            "veto.import." + reference,
                            Map.of(
                                    "veto.import.id", reference,
                                    "veto.import.service", "github",
                                    "veto.import.label", "Repository"),
                            "synthetic-token");
        }
    }

    private static @NonNull PluginHostServices hostServices(@NonNull KeysteadVault vault) {
        return new HostResourceConfiguration()
                .pluginHostServices(
                        PluginTestSupport.providerOf(vault), PluginTestSupport.providerOf(null));
    }

    private static @NonNull String reference(
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

    private static @NonNull ToolEngineImpl engineWith(
            @NonNull ObjectMapper mapper, @NonNull PluginManager plugins) {
        var context = mock(ApplicationContext.class);
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
        KeysteadVault vault = mock(KeysteadVault.class);
        var services = hostServices(vault);
        var service = services.services().get(VaultAccess.class);
        if (!(service instanceof VaultAccess access))
            throw new AssertionError("Vault host service missing");
        try (var plugins = PluginTestSupport.manager(services)) {
            var scope = new Scope.AgentScope("alice", session.toString(), "agent");
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
                    SecurityException.class, () -> open(access, reference, "github", "Repository"));

            installContext(call, definition, workspace, "alice", session, "agent");
            var writer = open(access, reference, "github", "Repository");
            assertEquals(
                    new Scope.AgentScope("alice", session.toString(), "agent"), writer.scope());
            // Even identical approved arguments on a new invocation do not renew a retained writer.
            installContext(call, definition, workspace, "alice", session, "agent");
            assertThrows(SecurityException.class, writer::isUnlocked);
            assertThrows(
                    SecurityException.class, () -> open(access, reference, "github", "Changed"));
            assertThrows(
                    SecurityException.class,
                    () -> open(access, reference, "other-service", "Repository"));
            assertThrows(
                    SecurityException.class,
                    () -> open(access, "other-reference", "github", "Repository"));

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
                        () -> open(access, reference, "github", "Repository"));
            }

            installContext(call, definition, workspace, null, session, "agent");
            assertThrows(
                    SecurityException.class, () -> open(access, reference, "github", "Repository"));
            installContext(call, definition, workspace, "alice", null, "agent");
            assertThrows(
                    SecurityException.class, () -> open(access, reference, "github", "Repository"));
            for (var other :
                    List.of(
                            new Scope.AgentScope("bob", session.toString(), "agent"),
                            new Scope.AgentScope("alice", UUID.randomUUID().toString(), "agent"),
                            new Scope.AgentScope("alice", session.toString(), "mate"))) {
                installContext(
                        call,
                        definition,
                        workspace,
                        other.owner(),
                        UUID.fromString(other.session()),
                        other.agent());
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

    private static VaultAccess.@NonNull Handle open(
            @NonNull VaultAccess access,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label) {
        return access.open(Map.of("secret_ref", reference, "service", service, "label", label));
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
                        .withCaller(agent, user, owner, session);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agent, user, owner, session, ToolResultPresentationMode.BASIC, permit));
        ToolCallContextHolder.setCurrentCallId(call.callId());
    }
}
