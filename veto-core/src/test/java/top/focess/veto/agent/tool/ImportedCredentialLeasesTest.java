package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.ImportedCredentialLeases;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.builtin.tools.ReadGitHubRepositoryTool;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.KeysteadVault;

class ImportedCredentialLeasesTest {
    @Test
    void leaseIsExactInvocationConfinedWipedAndRevoked() {
        @NonNull KeysteadVault vault = mock();
        @NonNull SessionRepository sessions = mock();
        var row = new SessionEntity("alice", "test");
        var id = UUID.fromString(row.getId());
        when(sessions.findById(row.getId())).thenReturn(Optional.of(row));
        when(vault.isUnlocked("alice")).thenReturn(true);
        var reference = "cred_01234567-89ab-cdef-0123-456789abcdef";
        doAnswer(
                        invocation -> {
                            Consumer<char[]> use = invocation.getArgument(3);
                            if (use == null) throw new AssertionError();
                            use.accept("secret".toCharArray());
                            return null;
                        })
                .when(vault)
                .withImportedCredential(eq("alice"), eq(reference), eq("custom-service"), any());
        var leases =
                new ImportedCredentialLeases(
                        vault,
                        sessions,
                        PluginTestSupport.providerOf(null),
                        PluginTestSupport.providerOf(null));
        var tool = new ReadGitHubRepositoryTool();
        var call = new ToolCall(tool.getName(), Map.of("credentialRef", reference), "call");
        var user = UUID.randomUUID();
        var permit =
                ToolExecutionPermit.capture(
                                call,
                                ToolSchemaCompiler.compileNative(tool),
                                Workspace.fromConfig("", "", "REAL"))
                        .withCaller("agent", user, "alice", id);
        var context =
                new ToolCallContext(
                        "agent", user, "alice", id, ToolResultPresentationMode.BASIC, permit);
        ToolCallContextHolder.set(context);
        ToolCallContextHolder.setCurrentCallId("call");
        try {
            assertThrows(SecurityException.class, () -> leases.open("unknown", "custom-service"));
            assertThrows(
                    SecurityException.class, () -> leases.open("credentialRef", "https://bad"));
            var lease = leases.open("credentialRef", "custom-service");
            var seen = new AtomicReference<char[]>();
            lease.use(
                    value -> {
                        assertEquals("secret", new String(value));
                        seen.set(value);
                    });
            var wiped = seen.get();
            if (wiped == null) throw new AssertionError();
            assertArrayEquals(new char[6], wiped);
            ToolCallContextHolder.setCurrentCallId("different");
            assertThrows(SecurityException.class, () -> lease.use(value -> fail("Replayed lease")));
            ToolCallContextHolder.setCurrentCallId("call");
            when(sessions.findById(row.getId()))
                    .thenReturn(Optional.of(new SessionEntity("bob", "other")));
            assertThrows(
                    SecurityException.class, () -> lease.use(value -> fail("Wrong session owner")));
            when(sessions.findById(row.getId())).thenReturn(Optional.of(row));
            ImportedCredentialLeases.releaseInvocation(context);
            assertThrows(
                    SecurityException.class, () -> lease.use(value -> fail("Ended invocation")));
            verify(vault, times(1))
                    .withImportedCredential(
                            eq("alice"), eq(reference), eq("custom-service"), any());
        } finally {
            ToolCallContextHolder.clear();
            tool.close();
        }
    }
}
