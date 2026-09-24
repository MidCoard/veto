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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.builtin.*;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.web.*;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.builtin.tools.ReadGitHubRepositoryTool;
import top.focess.veto.integration.plugins.PluginConfigurations;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.integration.plugins.secrets.SecretProtectionConfiguration;
import top.focess.veto.llm.core.*;
import top.focess.veto.sandbox.*;
import top.focess.veto.vault.*;

class CredentialJourneyTest {
    private static final @NonNull String IMPORT_TOOL =
            "plugin_top_focess_secret_protection__import_detected_credential";

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void fileImportAndAuthenticatedReadKeepSecretsOutOfModelAndHistory(
            boolean usePlan, boolean approveUse, @TempDir @NonNull Path directory)
            throws Exception {
        // Use the same physical workspace path as tool resolution, including on macOS where
        // the system temporary directory is reached through a symlink.
        Path root = directory.toRealPath();
        var mapper = new ObjectMapper();
        String token = "ghp_" + "A1".repeat(18);
        String pluginToken = "ghp_" + "B2".repeat(18);
        Path file = Files.writeString(root.resolve("config.txt"), "token=" + token);
        var configuration = new CredentialVaultConfiguration();
        configuration.setVaultHome(root.resolve("vault").toString());
        var vault = new KeysteadVault(configuration);
        vault.signup("owner", "test-password");
        var pluginConfiguration = new PluginConfigurations();
        pluginConfiguration.setToolNames(Map.of("top.focess.builtin:view_file", "view_file"));
        var plugins =
                new PluginManager(
                        "",
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(
                                new SecretProtectionConfiguration()
                                        .pluginHostServices(
                                                PluginTestSupport.providerOf(vault),
                                                PluginTestSupport.providerOf(null))),
                        pluginConfiguration);
        var sessionPlugins = PluginTestSupport.sessionPlugins(plugins);
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
        var reader =
                new GitHubRepositoryReader(vault, mapper, PluginTestSupport.providerOf(plugins));
        ReflectionTestUtils.setField(reader, "client", client);
        network.attachRepositoryReader(reader);
        var toolContext =
                mock(ToolDocs.nonNullClass(org.springframework.context.ApplicationContext.class));
        when(toolContext.getBeansOfType(top.focess.veto.api.agent.tool.AgentTool.class))
                .thenReturn(
                        Map.of(
                                "submit_plan",
                                new top.focess.veto.builtin.planning.SubmitPlanTool(
                                        new top.focess.veto.agent.capability
                                                .ResponseCapabilityImpl())));
        when(toolContext.getBeansOfType(PluginManager.class))
                .thenReturn(Map.of("plugins", plugins));
        var engine =
                new ToolEngineImpl(
                        mapper, List.of(new ReadGitHubRepositoryTool(network)), toolContext);
        engine.attachSessionPlugins(sessionPlugins);
        engine.afterSingletonsInstantiated();
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
                    assertFalse(
                            observed.contains(pluginToken),
                            "A plugin's final observation must be protected before the model sees it");
                    int step = calls.getAndIncrement();
                    if (step == 0) {
                        if (!usePlan)
                            return tool("view_file", Map.of("absolutePath", file.toString()));
                        String program =
                                """
                    [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"text":"content"}},
                     {"id":"reference","label":"Extract reference","type":"generate","prompt":"Extract the secret reference from $text","inputs":{"text":"$text"},"outputs":{"ref":"message"}},
                     {"id":"import","label":"Import","type":"tool","tool":"plugin_top_focess_secret_protection__import_detected_credential","inputs":{"secret_ref":"$ref","service":"github","label":"Repository"},"outputs":{"receipt":"content"}},
                     {"id":"credential","label":"Extract credential reference","type":"generate","prompt":"Extract the credential reference from $receipt","inputs":{"receipt":"$receipt"},"outputs":{"credential":"message"}},
                     {"id":"repository","label":"Read repository","type":"tool","tool":"read_github_repository","inputs":{"credentialRef":"$credential","repositoryOwner":"example","repositoryName":"project"},"outputs":{"repository":"content"}},
                     {"id":"answer","label":"Report","type":"generate","prompt":"Report $repository","inputs":{"repository":"$repository"},"outputs":{"answer":"message"}},
                     {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                    """;
                        try {
                            return new VetoResponse(
                                    null,
                                    java.util.List.of(
                                            new ToolCall(
                                                    "submit_plan",
                                                    Map.of(
                                                            "actions",
                                                            mapper.readTree(
                                                                    program.replace(
                                                                            "PATH",
                                                                            mapper
                                                                                    .writeValueAsString(
                                                                                            file
                                                                                                    .toString())))))),
                                    null);
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    }
                    if (step == 1) {
                        String ref = extract(observed, "s_[a-f0-9]{32}");
                        return usePlan
                                ? message(ref)
                                : tool(
                                        IMPORT_TOOL,
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
                        return usePlan
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
        AtomicInteger protectedFileObservations = new AtomicInteger();
        AtomicInteger protectedNetworkObservations = new AtomicInteger();
        var observationPlugin =
                new LoopInterceptor() {
                    @Override
                    public boolean preAction(@NonNull String agentId, @NonNull ToolCall call) {
                        return true;
                    }

                    @Override
                    public @NonNull ToolResult postAction(
                            @NonNull String agentId,
                            @NonNull ToolCall call,
                            @NonNull ToolResult result) {
                        return result;
                    }

                    @Override
                    public @NonNull String preObservation(
                            @NonNull String agentId, @NonNull String rawObservation) {
                        if (rawObservation.contains("[SECRET_REF:")) {
                            protectedFileObservations.incrementAndGet();
                        } else if (rawObservation.contains("\"full_name\"")) {
                            protectedNetworkObservations.incrementAndGet();
                        } else {
                            return rawObservation;
                        }
                        // Model-visible output must include the plugin's ordinary text but never
                        // the secret it introduces, even though the file's registered ref survives.
                        return rawObservation + "\nPlugin diagnostic: token=" + pluginToken;
                    }
                };
        var service =
                new AgentService(
                        engine,
                        hitl,
                        new IngressDefense(null, PluginTestSupport.providerOf(plugins)),
                        compiler,
                        caller,
                        mapper,
                        List.of(observationPlugin),
                        new RoleToolFilter(engine) {
                            @Override
                            public @NonNull Set<@NonNull ToolDefinition> resolve(
                                    @NonNull Role role,
                                    @NonNull Set<@NonNull ToolCapability> capabilities) {
                                // Keep this credential journey's original four-tool manifest.
                                return super.resolve(role, capabilities).stream()
                                        .filter(
                                                tool ->
                                                        Set.of(
                                                                        "view_file",
                                                                        "read_github_repository",
                                                                        "submit_plan",
                                                                        IMPORT_TOOL)
                                                                .contains(tool.name()))
                                        .collect(Collectors.toUnmodifiableSet());
                            }
                        },
                        "REAL",
                        50,
                        1000,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        null,
                        new BackgroundTaskManager(sandbox));
        service.attachSessionPlugins(sessionPlugins);
        service.attachLifecycleEvents(new PluginLifecycleEvents(plugins));
        service.setConfiguredDefaultWorkspace(Workspace.single(root, PathMode.REAL));
        String session = UUID.randomUUID().toString();
        var agent =
                service.getOrCreateAgent(
                        session,
                        UUID.randomUUID().toString(),
                        new LlmBinding(
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
                        ToolResultPresentationMode.BASIC);
        try {
            var result =
                    service.submit(
                            session,
                            "Read the configuration, import its credential and read example/project",
                            new LlmBinding(
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
            assertEquals(List.of(IMPORT_TOOL, "read_github_repository"), approvals);
            assertTrue(
                    agent.history().stream()
                            .noneMatch(turn -> turn.payload().toString().contains(token)));
            assertTrue(
                    agent.history().stream()
                            .noneMatch(turn -> turn.payload().toString().contains(pluginToken)),
                    "Plugin secrets must be removed before the tool result enters history");
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.payload()
                                                    .toString()
                                                    .contains("Plugin diagnostic:")),
                    "Protect the plugin's result instead of dropping the plugin's output");
            assertEquals(1, protectedFileObservations.get());
            assertEquals(approveUse ? 1 : 0, protectedNetworkObservations.get());
            assertEquals("token=" + token, Files.readString(file));
        } finally {
            service.remove(session);
            plugins.close();
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
                null, List.of(new ToolCall(name, args, UUID.randomUUID().toString())), null);
    }

    private static @NonNull VetoResponse message(@NonNull String value) {
        return new VetoResponse(null, null, value);
    }
}
