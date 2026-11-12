package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.integration.plugins.HostResourceConfiguration;
import top.focess.veto.integration.plugins.PluginHostServices;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class CredentialImportIntegrationTest {
    private static final @NonNull String IMPORT_TOOL = "import_credential";

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    private static @NonNull PluginHostServices hostServices(@NonNull KeysteadVault vault) {
        return new HostResourceConfiguration()
                .pluginHostServices(
                        PluginTestSupport.providerOf(vault), PluginTestSupport.providerOf(null));
    }

    private static @NonNull ToolEngineImpl engineWith(
            @NonNull ObjectMapper mapper, @NonNull PluginManager plugins) {
        var context = mock(ApplicationContext.class);
        when(context.getBeansOfType(PluginManager.class)).thenReturn(Map.of("plugins", plugins));
        var engine =
                new ToolEngineImpl(
                        mapper,
                        List.of(),
                        context,
                        PluginTestSupport.sessionPlugins(plugins),
                        PluginTestSupport.eventManager(plugins));
        engine.afterSingletonsInstantiated();
        return engine;
    }

    private static @NonNull ToolDefinition importTool(@NonNull ToolEngineImpl engine) {
        return engine.getActiveTools(Set.of(IMPORT_TOOL)).stream()
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
            var scope = new Scope.AgentScope(TestUsers.ALICE, session.toString(), "agent");
            String reference = "synthetic-token";
            var engine = engineWith(new ObjectMapper(), plugins);
            var definition = importTool(engine);
            var workspace = Workspace.single(directory, PathMode.REAL);
            var call =
                    new ToolCall(
                            IMPORT_TOOL,
                            Map.of(
                                    "value",
                                    reference,
                                    "visibility",
                                    "PRIVATE",
                                    "service",
                                    "github",
                                    "label",
                                    "Repository"),
                            "call");
            assertThrows(
                    SecurityException.class, () -> open(access, reference, "github", "Repository"));

            installContext(call, definition, workspace, TestUsers.ALICE, session, "agent");
            var writer = open(access, reference, "github", "Repository");
            assertEquals(
                    new Scope.AgentScope(TestUsers.ALICE, session.toString(), "agent"),
                    writer.scope());
            // Even identical approved arguments on a new invocation do not renew a retained writer.
            installContext(call, definition, workspace, TestUsers.ALICE, session, "agent");
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
                                            "value",
                                            reference,
                                            "visibility",
                                            "PRIVATE",
                                            "service",
                                            "github",
                                            "label",
                                            "Repository",
                                            "extra",
                                            "not approved"),
                                    call.callId()))) {
                installContext(
                        incompatibleCall, definition, workspace, TestUsers.ALICE, session, "agent");
                assertThrows(
                        SecurityException.class,
                        () -> open(access, reference, "github", "Repository"));
            }

            installContext(call, definition, workspace, TestUsers.ALICE, session, "agent");
            var aliceWriter = open(access, reference, "github", "Repository");
            installContext(call, definition, workspace, TestUsers.BOB, session, "agent");
            assertThrows(SecurityException.class, aliceWriter::isUnlocked);
            assertThrows(SecurityException.class, aliceWriter::scope);
            var bobWriter = open(access, reference, "github", "Repository");
            assertEquals(
                    new Scope.AgentScope(TestUsers.BOB, session.toString(), "agent"),
                    bobWriter.scope());
            assertThrows(
                    SecurityException.class,
                    () -> invokeImport(plugins, reference, "github", "Repository"));
            installContext(call, definition, workspace, TestUsers.ALICE, null, "agent");
            assertThrows(
                    SecurityException.class, () -> open(access, reference, "github", "Repository"));
            for (var other :
                    List.of(
                            new Scope.AgentScope(TestUsers.BOB, session.toString(), "agent"),
                            new Scope.AgentScope(
                                    TestUsers.ALICE, UUID.randomUUID().toString(), "agent"),
                            new Scope.AgentScope(TestUsers.ALICE, session.toString(), "mate"))) {
                installContext(
                        call,
                        definition,
                        workspace,
                        other.userId(),
                        UUID.fromString(other.session()),
                        other.agent());
                assertThrows(
                        SecurityException.class,
                        () -> invokeImport(plugins, reference, "github", "Repository"));
            }
            verifyNoInteractions(vault);
        }
    }

    private static VaultAccess.@NonNull Handle open(
            @NonNull VaultAccess access,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label) {
        return access.open(
                Map.of(
                        "value",
                        reference,
                        "visibility",
                        "PRIVATE",
                        "service",
                        service,
                        "label",
                        label));
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
                                                && tool.getName().equals("import_credential"))
                        .findFirst()
                        .orElseThrow();
        CapabilityTool<?> tool = (CapabilityTool<?>) entry.implementation();
        var mapper = new ObjectMapper();
        Object args =
                Nullness.requireNonNull(
                        mapper.treeToValue(
                                mapper.valueToTree(
                                        Map.of(
                                                "value", reference,
                                                "visibility", "PRIVATE",
                                                "service", service,
                                                "label", label)),
                                tool.getArgsClass()));
        return ((CapabilityTool) tool).execute(args);
    }

    private static void installContext(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Workspace workspace,
            @NonNull UUID userId,
            UUID session,
            @NonNull String agent) {
        var user = userId;
        var permit =
                ToolExecutionPermit.capture(call, definition, workspace)
                        .withCaller(agent, user, session);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agent, user, session, ToolResultPresentationMode.BASIC, permit));
        ToolCallContextHolder.setCurrentCallId(call.callId());
    }
}
