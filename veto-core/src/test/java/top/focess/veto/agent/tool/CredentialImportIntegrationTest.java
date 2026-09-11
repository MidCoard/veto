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
import top.focess.veto.agent.capability.CredentialImportCapabilityImpl;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.tool.builtin.ImportDetectedCredentialTool;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SecretCandidateStore;

class CredentialImportIntegrationTest {

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void screenedImportBindsCallerAndArgumentsBeforeStorage(@TempDir @NonNull Path directory)
            throws Exception {
        var session = UUID.randomUUID();
        var user = UUID.randomUUID();
        var scope = new SecretCandidateStore.Scope("alice", session.toString(), "agent");
        var store = new SecretCandidateStore();
        var reference =
                store.capture(scope, "source", "password=synthetic-token")
                        .candidates()
                        .getFirst()
                        .reference();
        @NonNull KeysteadVault vault = mock();
        when(vault.isUnlocked("alice")).thenReturn(true);
        when(vault.createImportedCredential(
                        "alice", reference, "github", "Repository", "synthetic-token"))
                .thenReturn("cred_test");
        var mapper = new ObjectMapper();
        var tool =
                new ImportDetectedCredentialTool(
                        new CredentialImportCapabilityImpl(store, vault, mapper));
        var engine = ToolEngineImpl.isolated(mapper, List.of(tool));
        var definition = engine.getActiveTools(null).getFirst();
        var workspace = Workspace.single(directory, PathMode.REAL);
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
                        tool.getName(),
                        Map.of("secret_ref", reference, "service", "github", "label", "Repository"),
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
                        false,
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
                        false,
                        permit));
        var changed =
                new ToolCall(
                        tool.getName(),
                        Map.of("secret_ref", reference, "service", "github", "label", "Changed"),
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

    @Test
    void rolesAndNativeContractKeepImportWithinItsOwnBoundary() {
        assertTrue(
                RoleToolFilter.capabilitiesFor(Role.STANDALONE)
                        .contains(ToolCapability.CREDENTIAL_IMPORT));
        assertTrue(
                RoleToolFilter.capabilitiesFor(Role.MATE)
                        .contains(ToolCapability.CREDENTIAL_IMPORT));
        assertFalse(
                RoleToolFilter.capabilitiesFor(Role.LEADER)
                        .contains(ToolCapability.CREDENTIAL_IMPORT));
        var tool =
                new ImportDetectedCredentialTool(
                        new CredentialImportCapabilityImpl(
                                new SecretCandidateStore(),
                                mock(ToolDocs.nonNullClass(KeysteadVault.class)),
                                new ObjectMapper()));
        var def = ToolSchemaCompiler.compileNative(tool);
        var unsafe =
                new NativeToolDefinition(
                        def.name(),
                        def.description(),
                        def.capability(),
                        Danger.SAFE,
                        false,
                        def.toolClass(),
                        def.argsClass(),
                        def.paramHints());
        assertThrows(IllegalArgumentException.class, () -> ToolContractValidator.validate(unsafe));
    }
}
