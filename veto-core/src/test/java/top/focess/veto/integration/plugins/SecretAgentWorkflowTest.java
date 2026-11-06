package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.secret.SecretAgent;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

/** The model response is scripted; admission, tools, vault authority and hooks are real. */
class SecretAgentWorkflowTest {
    private static final @NonNull String IMPORT =
            "plugin_top_focess_secret_protection__import_credential";
    private static final @NonNull String READ =
            "plugin_top_focess_secret_protection__read_credential";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void childImportsThroughOrdinaryScreeningAndPrimaryReadsOnlyApprovedValues(
            boolean readable, @TempDir @NonNull Path directory) throws Exception {
        var session = UUID.randomUUID();
        var owner = new Scope.AgentScope(TestUsers.ALICE, session.toString(), "primary");
        var workspace = Workspace.single(directory, PathMode.REAL);
        var engineRef = new AtomicReference<ToolEngineImpl>();
        var vault = mock(KeysteadVault.class);
        when(vault.isUnlocked(TestUsers.ALICE)).thenReturn(true);
        when(vault.createSecureNoteIfAbsent(
                        eq(TestUsers.ALICE), anyString(), anyMap(), eq("AbC123!")))
                .thenReturn("cred_test");
        var gateway =
                new Gateway(
                        workspace,
                        new DangerComputation(),
                        SlmScreeningProvider.unavailable(),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty(),
                        new ReadHistory());
        var hitl = new HitlRegistry(null, mock(SessionActions.class));
        PluginAgentHostFactory hosts =
                (plugin, storage) ->
                        grant -> {
                            storage.session(grant);
                            return new AgentHost.Session() {
                                public @NonNull String id() {
                                    return session.toString();
                                }

                                public @NonNull String primaryAgentId() {
                                    return "primary";
                                }

                                public AgentHost.@NonNull Child open(
                                        @NonNull String id,
                                        @NonNull String parent,
                                        @NonNull AgentProfile profile) {
                                    assertEquals("primary", parent);
                                    var binding = profile.model();
                                    if (binding == null)
                                        throw new AssertionError("Expected explicit model binding");
                                    assertEquals(
                                            "./models/veto-secret-agent.gguf", binding.baseUrl());
                                    assertEquals(Set.of(IMPORT), profile.tools());
                                    return new AgentHost.Child() {
                                        private boolean closed;

                                        public @NonNull String id() {
                                            return id;
                                        }

                                        public @NonNull AgentState state() {
                                            return AgentState.IDLE;
                                        }

                                        public AgentHost.@NonNull Request submit(
                                                @NonNull String text) {
                                            assertEquals(
                                                    SecretAgent.sourceInput("password=AbC123!"),
                                                    text);
                                            var engine = Nullness.requireNonNull(engineRef.get());
                                            var definition =
                                                    Nullness.requireNonNull(
                                                            engine.resolveDefinition(IMPORT));
                                            var call =
                                                    new ToolCall(
                                                            IMPORT,
                                                            Map.of(
                                                                    "value",
                                                                    "AbC123!",
                                                                    "visibility",
                                                                    readable ? "PUBLIC" : "PRIVATE",
                                                                    "service",
                                                                    "github",
                                                                    "label",
                                                                    "Repository"),
                                                            "import-call");
                                            var invocation =
                                                    new PluginHost.Invocation(
                                                            TestUsers.ALICE,
                                                            session.toString(),
                                                            id,
                                                            null,
                                                            call.callId());
                                            var prepared =
                                                    engine.prepare(call, definition, invocation);
                                            var screened =
                                                    assertInstanceOf(
                                                            GatewayResult.Screened.class,
                                                            gateway.screen(
                                                                    call,
                                                                    definition,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    prepared));
                                            var decision =
                                                    hitl.decide(id, call, definition, screened);
                                            if (readable)
                                                assertInstanceOf(
                                                        ApprovalDecision.Prompt.class, decision);
                                            else
                                                assertInstanceOf(
                                                        ApprovalDecision.AutoApprove.class,
                                                        decision);
                                            // Simulate accepting that exact prompt; changed args
                                            // are still rejected.
                                            var permit =
                                                    gateway.revalidateExecution(
                                                                    call,
                                                                    definition,
                                                                    screened.executionPermit())
                                                            .withCaller(
                                                                    id, TestUsers.ALICE, session);
                                            ToolCallContextHolder.set(
                                                    new ToolCallContext(
                                                            id,
                                                            TestUsers.ALICE,
                                                            session,
                                                            ToolResultPresentationMode.BASIC,
                                                            permit));
                                            try {
                                                var changed =
                                                        new ToolCall(
                                                                IMPORT,
                                                                Map.of(
                                                                        "value",
                                                                        "Other123!",
                                                                        "visibility",
                                                                        readable
                                                                                ? "PUBLIC"
                                                                                : "PRIVATE",
                                                                        "service",
                                                                        "github",
                                                                        "label",
                                                                        "Repository"),
                                                                call.callId());
                                                assertFalse(
                                                        engine.execute(changed, definition)
                                                                .success());
                                                var result = engine.execute(call, definition);
                                                assertTrue(result.success(), result.content());
                                                assertEquals("cred_test", result.content());
                                            } finally {
                                                ToolCallContextHolder.clear();
                                            }
                                            return new AgentHost.Request() {
                                                public @NonNull String id() {
                                                    return "request";
                                                }

                                                public @NonNull CompletableFuture<AgentResult>
                                                        result() {
                                                    return CompletableFuture.completedFuture(
                                                            AgentResult.success("DONE", Map.of()));
                                                }

                                                public @NonNull CompletableFuture<Boolean>
                                                        settled() {
                                                    return CompletableFuture.completedFuture(true);
                                                }

                                                public boolean cancel(@NonNull Duration timeout) {
                                                    return true;
                                                }
                                            };
                                        }

                                        public void close() {
                                            closed = true;
                                        }

                                        public boolean awaitTermination(@NonNull Duration timeout) {
                                            return closed;
                                        }
                                    };
                                }
                            };
                        };
        var services =
                new HashMap<Class<?>, Object>(
                        new HostResourceConfiguration()
                                .pluginHostServices(
                                        PluginTestSupport.providerOf(vault),
                                        PluginTestSupport.providerOf(null))
                                .services());
        services.put(PluginAgentHostFactory.class, hosts);
        try (var plugins = PluginTestSupport.manager(new PluginHostServices(services))) {
            var context = mock(ApplicationContext.class);
            when(context.getBeansOfType(PluginManager.class))
                    .thenReturn(Map.of("plugins", plugins));
            var engine =
                    new ToolEngineImpl(
                            new ObjectMapper(),
                            List.of(),
                            context,
                            PluginTestSupport.sessionPlugins(plugins),
                            PluginTestSupport.eventManager(plugins));
            engine.afterSingletonsInstantiated();
            engineRef.set(engine);
            assertTrue(
                    engine.getActiveTools(null).stream()
                            .noneMatch(tool -> tool.name().equals(IMPORT)));
            var selected =
                    AgentProfiles.resolve(
                            "child",
                            owner.userId(),
                            SecretAgent.profile(true),
                            Set.copyOf(engine.getActiveTools(Set.of(IMPORT))),
                            new LlmBinding(
                                    ProviderType.LOCAL,
                                    "top.focess.secret-protection",
                                    "",
                                    LlmOptions.defaults(),
                                    null),
                            mock(ModelTierRegistry.class),
                            PluginTestSupport.sessionPlugins(plugins)
                                    .renderPrompt(
                                            "top.focess.secret-protection",
                                            SecretAgent.profile(true).prompt()));
            var guidance = selected.prompt();
            if (guidance == null) throw new AssertionError("Plugin guidance was not compiled");
            assertTrue(guidance.contains("The host-attributed source is user input."));
            assertEquals(
                    Set.of(IMPORT),
                    selected.persona().whitelistedTools().stream()
                            .map(tool -> tool.name())
                            .collect(Collectors.toSet()));
            var invocationScope = new PluginInvocationContext(owner.userId(), owner.session());
            try (invocationScope) {
                assertEquals(
                        "password=[SECRET_REF:cred_test]",
                        PluginTestSupport.protect(
                                plugins,
                                BeforeTextCommitEvent.Phase.INPUT,
                                owner,
                                "input",
                                "password=AbC123!"));
            }
            var definition = Nullness.requireNonNull(engine.resolveDefinition(READ));
            var call = new ToolCall(READ, Map.of("reference", "cred_test"), "read-call");
            var screened =
                    assertInstanceOf(
                            GatewayResult.Screened.class, gateway.screen(call, definition));
            var permit =
                    gateway.revalidateExecution(call, definition, screened.executionPermit())
                            .withCaller(owner.agent(), owner.userId(), session);
            ToolCallContextHolder.set(
                    new ToolCallContext(
                            owner.agent(),
                            owner.userId(),
                            session,
                            ToolResultPresentationMode.BASIC,
                            permit));
            try {
                var result = engine.execute(call, definition);
                assertEquals(readable, result.success());
                assertEquals(
                        readable,
                        result.options()
                                .bypassedProcessors()
                                .contains("top.focess.secret-protection"));
                if (readable) assertEquals("AbC123!", result.content());
            } finally {
                ToolCallContextHolder.clear();
            }
            verify(vault)
                    .createSecureNoteIfAbsent(
                            eq(TestUsers.ALICE),
                            anyString(),
                            argThat(
                                    attributes ->
                                            (readable ? "public" : "private")
                                                    .equals(
                                                            attributes.get(
                                                                    "veto.import.visibility"))),
                            eq("AbC123!"));
        }
    }
}
