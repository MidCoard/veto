package top.focess.veto.builtin.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.credentials.ImportedCredentialLease;
import top.focess.veto.builtin.tools.ReadGitHubRepositoryTool;

class GitHubToolTest {
    @Test
    void boundedFixedRequestFiltersDecodedCredentialAndClosesLease() throws Exception {
        @NonNull HttpClient client = mock();
        @NonNull HttpResponse<byte[]> response = mock();
        @NonNull NetworkEgressCapability capability = mock();
        boolean[] closed = {false};
        when(capability.openImportedCredential("credentialRef", "github"))
                .thenReturn(
                        new ImportedCredentialLease() {
                            public void use(@NonNull Consumer<char @NonNull []> use) {
                                use.accept("token".toCharArray());
                            }

                            public void close() {
                                closed[0] = true;
                            }
                        });
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        "{\"id\":1,\"private\":true,\"full_name\":\"owner/repo\",\"description\":\"to\\u006ben\",\"ignored\":\"token\"}"
                                .getBytes(StandardCharsets.UTF_8));
        when(client.<byte[]>send(any(), any()))
                .thenAnswer(
                        invocation -> {
                            HttpRequest request = invocation.getArgument(0);
                            if (request == null) throw new AssertionError();
                            assertEquals(
                                    "https://api.github.com/repos/owner/repo",
                                    request.uri().toString());
                            assertEquals("GET", request.method());
                            assertEquals(15, request.timeout().orElseThrow().toSeconds());
                            assertEquals(
                                    "Bearer token",
                                    request.headers().firstValue("Authorization").orElseThrow());
                            return response;
                        });
        try (var tool = new ReadGitHubRepositoryTool(capability, client)) {
            var args = new ReadGitHubRepositoryTool.Args("ref", "owner", "repo");
            var result = tool.execute(args, capability);
            assertTrue(result.contains("[REDACTED_CREDENTIAL]"));
            assertFalse(result.contains("ignored"));
            assertFalse(result.contains("token"));
            assertTrue(closed[0]);
            when(response.statusCode()).thenReturn(302);
            var redirect =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> tool.execute(args, capability));
            assertTrue(String.valueOf(redirect.getMessage()).contains("302"));
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(new byte[1_048_577]);
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> tool.execute(args, capability));
            verify(client, times(3)).send(any(), any());
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            tool.execute(
                                    new ReadGitHubRepositoryTool.Args("ref", "../bad", "repo"),
                                    capability));
            verifyNoMoreInteractions(client);
        }
    }
}
