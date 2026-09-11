package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.tool.builtin.ReadGitHubRepositoryTool;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.llm.core.*;
import top.focess.veto.vault.*;

class GitHubRepositoryReaderTest {
    @Test
    void approvedBindingUsesFixedDestinationAndFiltersCredentialEcho(@TempDir @NonNull Path root)
            throws Exception {
        var configuration = new CredentialVaultConfiguration();
        configuration.setVaultHome(root.toString());
        var vault = new KeysteadVault(configuration);
        @NonNull HttpClient client = mock();
        @NonNull HttpResponse<byte[]> response = mock();
        String token = "synthetic-token";
        try {
            vault.signup("alice", "test-password");
            String reference =
                    vault.createImportedCredential(
                            "alice",
                            "s_0123456789abcdef0123456789abcdef",
                            "github",
                            "Repository",
                            token);
            when(response.statusCode()).thenReturn(200);
            when(response.body())
                    .thenReturn(
                            ("{\"id\":12,\"private\":true,\"full_name\":\"example/project\","
                                            + "\"description\":\"synthetic-token\",\"default_branch\":\"main\",\"ignored\":\"synthetic-token\"}")
                                    .getBytes(StandardCharsets.UTF_8));
            when(client.<byte[]>send(any(), any()))
                    .thenAnswer(
                            invocation -> {
                                HttpRequest request = invocation.getArgument(0);
                                if (request == null) throw new AssertionError("Missing request");
                                assertEquals(
                                        "https://api.github.com/repos/example/project",
                                        request.uri().toString());
                                assertEquals("GET", request.method());
                                assertEquals(
                                        "Bearer " + token,
                                        request.headers()
                                                .firstValue("Authorization")
                                                .orElseThrow());
                                return response;
                            });
            var reader = new GitHubRepositoryReader(vault, new ObjectMapper(), client);
            @NonNull SearchProvider provider = mock();
            @NonNull WebFetchExecutor fetch = mock();
            var capability = new NetworkEgressCapabilityImpl(provider, fetch, 15, 1000000, false);
            capability.attachRepositoryReader(reader);
            var tool = new ReadGitHubRepositoryTool(capability);
            var args =
                    Map.<String, Object>of(
                            "credentialRef",
                            reference,
                            "repositoryOwner",
                            "example",
                            "repositoryName",
                            "project");
            var call = new ToolCall(tool.getName(), args, "approved-call");
            UUID user = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            var permit =
                    ToolExecutionPermit.capture(
                                    call,
                                    ToolSchemaCompiler.compileNative(tool),
                                    Workspace.single(root, PathMode.REAL))
                            .withCaller("agent", user, null, "alice", session);
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
            var engine = ToolEngineImpl.isolated(new ObjectMapper(), List.of(tool));
            var definition = engine.getActiveTools(null).getFirst();
            String result = engine.execute(call, definition).content();
            assertTrue(result.contains("[REDACTED_CREDENTIAL]"), result);
            assertFalse(result.contains(token));
            assertFalse(result.contains("ignored"));
            assertTrue(new ObjectMapper().readTree(result).path("private").booleanValue());
            var changed =
                    new ToolCall(
                            tool.getName(),
                            Map.of(
                                    "credentialRef",
                                    reference,
                                    "repositoryOwner",
                                    "example",
                                    "repositoryName",
                                    "other"),
                            call.callId());
            assertFalse(engine.execute(changed, definition).success());
            verify(client, times(1)).send(any(), any());
            when(response.statusCode()).thenReturn(302);
            assertTrue(engine.execute(call, definition).content().contains("302"));
            verify(client, times(2)).send(any(), any());
            when(response.statusCode()).thenReturn(401);
            var unauthorized = engine.execute(call, definition);
            assertFalse(unauthorized.success());
            assertTrue(unauthorized.content().contains("401"));
            assertFalse(unauthorized.content().contains(token));
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(token.getBytes(StandardCharsets.UTF_8));
            var malformed = engine.execute(call, definition);
            assertFalse(malformed.success());
            assertFalse(malformed.content().contains(token));
            doThrow(new HttpTimeoutException(token)).when(client).send(any(), any());
            var timeout = engine.execute(call, definition);
            assertFalse(timeout.success());
            assertFalse(timeout.content().contains(token));
            doThrow(new InterruptedException(token)).when(client).send(any(), any());
            try {
                var interrupted = engine.execute(call, definition);
                assertFalse(interrupted.success());
                assertFalse(interrupted.content().contains(token));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            verify(client, times(6)).send(any(), any());
            vault.logoutAll();
            var locked = engine.execute(call, definition);
            assertFalse(locked.success());
            assertFalse(locked.content().contains(token));
            verifyNoMoreInteractions(client);
        } finally {
            ToolCallContextHolder.clear();
            vault.logoutAll();
        }
    }
}
