package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.capability.*;
import top.focess.veto.agent.identity.*;
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.tool.builtin.*;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.web.*;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.llm.core.*;
import top.focess.veto.sandbox.*;
import top.focess.veto.vault.*;

class CredentialJourneyTest {
    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void fileImportAndAuthenticatedReadKeepSecretsOutOfModelAndHistory(
            boolean guided, boolean approveUse, @TempDir @NonNull Path root) throws Exception {
        var mapper = new ObjectMapper();
        String token = "ghp_" + "A1".repeat(18);
        Path file = Files.writeString(root.resolve("config.txt"), "token=" + token);
        var configuration = new CredentialVaultConfiguration();
        configuration.setVaultHome(root.resolve("vault").toString());
        var vault = new KeysteadVault(configuration);
        vault.signup("owner", "test-password");
        var candidates = new SecretCandidateStore();
        @NonNull HttpClient client = mock();
        @NonNull HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        ("{\"id\":12,\"private\":true,\"full_name\":\"example/project\",\"description\":\""
                                        + token
                                        + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
        when(client.<byte[]>send(any(), any()))
                .thenAnswer(
                        invocation -> {
                            HttpRequest request = invocation.getArgument(0);
                            if (request == null) throw new AssertionError("Request missing");
                            assertEquals(
                                    "https://api.github.com/repos/example/project",
                                    request.uri().toString());
                            assertEquals(
                                    "Bearer " + token,
                                    request.headers().firstValue("Authorization").orElseThrow());
                            return response;
                        });
        @NonNull SearchProvider search = mock();
        @NonNull WebFetchExecutor fetch = mock();
        var network = new NetworkEgressCapabilityImpl(search, fetch, 15, 1000000, false);
        var reader = new GitHubRepositoryReader(vault, mapper);
        ReflectionTestUtils.setField(reader, "client", client);
        network.attachRepositoryReader(reader);
        var engine =
                ToolEngineImpl.isolated(
                        mapper,
                        List.of(
                                new ViewFileTool(
                                        new ProtectedWorkspaceReadCapabilityImpl(candidates)),
                                new ImportDetectedCredentialTool(
                                        new CredentialImportCapabilityImpl(
                                                candidates, vault, mapper)),
                                new ReadGitHubRepositoryTool(network)));
        var compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        AtomicInteger calls = new AtomicInteger();
        var importedReferences = new ArrayList<String>();
        UniformLLMCaller caller =
                request -> {
                    String observed =
                            request.messages().stream()
                                    .map(m -> m.content())
                                    .reduce("", (left, right) -> left + "\n" + right);
                    assertFalse(observed.contains(token));
                    int step = calls.getAndIncrement();
                    if (step == 0) {
                        if (!guided)
                            return tool("view_file", Map.of("absolutePath", file.toString()));
                        String program =
                                """
                    [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"text":"content"}},
                     {"id":"reference","label":"Extract reference","type":"generate","prompt":"Extract the secret reference from $text","inputs":{"text":"$text"},"outputs":{"ref":"message"}},
                     {"id":"import","label":"Import","type":"tool","tool":"import_detected_credential","inputs":{"secret_ref":"$ref","service":"github","label":"Repository"},"outputs":{"receipt":"content"}},
                     {"id":"credential","label":"Extract credential reference","type":"generate","prompt":"Extract the credential reference from $receipt","inputs":{"receipt":"$receipt"},"outputs":{"credential":"message"}},
                     {"id":"repository","label":"Read repository","type":"tool","tool":"read_github_repository","inputs":{"credentialRef":"$credential","repositoryOwner":"example","repositoryName":"project"},"outputs":{"repository":"content"}},
                     {"id":"answer","label":"Report","type":"generate","prompt":"Report $repository","inputs":{"repository":"$repository"},"outputs":{"answer":"message"}},
                     {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                    """;
                        try {
                            return new VetoResponse(
                                    null,
                                    null,
                                    null,
                                    new VetoResponse.Guide(
                                            mapper.readTree(
                                                    program.replace(
                                                            "PATH",
                                                            mapper.writeValueAsString(
                                                                    file.toString())))));
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    }
                    if (step == 1) {
                        String ref = extract(observed, "s_[a-f0-9]{32}");
                        return guided
                                ? message(ref)
                                : tool(
                                        "import_detected_credential",
                                        Map.of(
                                                "secret_ref",
                                                ref,
                                                "service",
                                                "github",
                                                "label",
                                                "Repository"));
                    }
                    if (step == 2) {
                        String ref = extract(observed, "cred_[a-f0-9-]{36}");
                        importedReferences.add(ref);
                        return guided
                                ? message(ref)
                                : tool(
                                        "read_github_repository",
                                        Map.of(
                                                "credentialRef",
                                                ref,
                                                "repositoryOwner",
                                                "example",
                                                "repositoryName",
                                                "project"));
                    }
                    assertTrue(approveUse, "Refused use must not trigger another model call");
                    assertEquals(3, step);
                    assertTrue(observed.contains("example/project"));
                    return message("Private repository read successfully");
                };
        var sandbox = new SandboxManager(TestSandboxFactory.uncontainedSubprocesses());
        var hitl = new HitlRegistry();
        var approvals = new ArrayList<String>();
        var service =
                new AgentService(
                        engine,
                        hitl,
                        new IngressDefense(),
                        compiler,
                        caller,
                        mapper,
                        List.of(),
                        new RoleToolFilter(engine),
                        "REAL",
                        50,
                        1000,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        null,
                        new BackgroundTaskManager(sandbox));
        service.attachSecretCandidates(candidates);
        service.setConfiguredDefaultWorkspace(Workspace.single(root, PathMode.REAL));
        String session = UUID.randomUUID().toString();
        var agent =
                service.getOrCreateAgent(
                        session,
                        UUID.randomUUID().toString(),
                        new AgentRunner.LlmBinding(
                                ProviderType.DEEPSEEK,
                                "scripted",
                                "key",
                                LlmOptions.defaults(),
                                null),
                        List.of(),
                        UUID.randomUUID(),
                        "owner",
                        root.toString(),
                        0,
                        ToolResultPresentationMode.BASIC,
                        guided);
        try {
            var result =
                    service.submit(
                            session,
                            "Read the configuration, import its credential and read example/project",
                            new AgentRunner.LlmBinding(
                                    ProviderType.DEEPSEEK,
                                    "scripted",
                                    "key",
                                    LlmOptions.defaults(),
                                    null),
                            Duration.ofSeconds(15),
                            null,
                            prompt -> {
                                assertFalse(prompt.args().toString().contains(token));
                                approvals.add(prompt.tool());
                                var option =
                                        prompt.options().stream()
                                                .filter(
                                                        value ->
                                                                !approveUse
                                                                                && prompt.tool()
                                                                                        .equals(
                                                                                                "read_github_repository")
                                                                        ? value.isRefusal()
                                                                                && !value
                                                                                        .isDeclineAndContinue()
                                                                        : !value.isRefusal()
                                                                                && !value
                                                                                        .createsGrant())
                                                .findFirst()
                                                .orElseThrow();
                                assertTrue(
                                        hitl.resolveOption(
                                                prompt.agentId(), prompt.callId(), option.name()));
                            });
            if (approveUse) {
                assertTrue(result.success(), result.message());
                assertEquals("Private repository read successfully", result.message());
                assertEquals(4, calls.get());
                verify(client, times(1)).send(any(), any());
            } else {
                assertFalse(result.success(), result.message());
                assertTrue(
                        result.message().contains("The tool was not executed"), result.message());
                assertEquals(3, calls.get());
                verifyNoInteractions(client);
            }
            assertEquals(1, importedReferences.size());
            vault.withImportedCredential(
                    "owner",
                    importedReferences.getFirst(),
                    "github",
                    value -> assertEquals(token, new String(value)));
            assertEquals(
                    List.of("import_detected_credential", "read_github_repository"), approvals);
            assertTrue(
                    agent.history().stream()
                            .noneMatch(turn -> turn.payload().toString().contains(token)));
            assertEquals("token=" + token, Files.readString(file));
        } finally {
            service.remove(session);
            vault.logoutAll();
        }
    }

    private static @NonNull String extract(@NonNull String text, @NonNull String pattern) {
        var matcher = Pattern.compile(pattern).matcher(text);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        String reference = matcher.group();
        while (matcher.find()) reference = matcher.group();
        return reference;
    }

    private static @NonNull VetoResponse tool(
            @NonNull String name, @NonNull Map<String, Object> args) {
        return new VetoResponse(
                null, List.of(new ToolCall(name, args, UUID.randomUUID().toString())), null, null);
    }

    private static @NonNull VetoResponse message(@NonNull String value) {
        return new VetoResponse(null, null, value, null);
    }
}
