package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import top.focess.veto.VetoApplication;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/**
 * Starts the real Veto application and HTTP listener, then dispatches through its real tool engine.
 */
@SpringBootTest(
        classes = VetoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "veto.plugins.trusted-code=true",
            "veto.slm.enabled=false",
            "veto.terminal.enabled=false",
            "veto.bus.websocket.port=0",
            "veto.bus.grpc.port=0",
            "veto.observability.encryption-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:script_plugin_boot;DB_CLOSE_DELAY=-1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SuppressWarnings("initialization.fields.uninitialized")
class ScriptPluginBootTest {
    private static final @NonNull Path STATE = stateDirectory();
    @LocalServerPort private int port;
    private final @NonNull ToolEngine engine;
    private final @NonNull PluginManager plugins;
    @Autowired private top.focess.veto.model.SessionRepository sessions;
    @Autowired private SessionPlugins selection;

    @Autowired
    ScriptPluginBootTest(@NonNull ToolEngine engine, @NonNull PluginManager plugins) {
        this.engine = engine;
        this.plugins = plugins;
    }

    private static @NonNull Path stateDirectory() {
        try {
            return Files.createTempDirectory("veto-plugin-boot-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @NonNull String node() {
        for (String item :
                Objects.requireNonNullElse(System.getenv("PATH"), "").split(File.pathSeparator)) {
            Path candidate =
                    Path.of(
                            item,
                            System.getProperty("os.name", "").startsWith("Windows")
                                    ? "node.exe"
                                    : "node");
            if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
        }
        throw new IllegalStateException("Node.js is required for plugin startup tests");
    }

    @DynamicPropertySource
    static void configure(@NonNull DynamicPropertyRegistry registry) {
        registry.add(
                "veto.plugins.paths",
                () ->
                        Path.of("../veto-plugin-runtime/examples/text-tools")
                                .toAbsolutePath()
                                .normalize()
                                .toString());
        registry.add("veto.plugins.node-command", ScriptPluginBootTest::node);
        registry.add("veto.vault.vault-home", () -> STATE.resolve("vault").toString());
    }

    @Test
    void startedApplicationRegistersAndDispatchesApprovedPluginTool() throws Exception {
        assertEquals(1, plugins.scriptPlugins().size());
        assertTrue(plugins.scriptPlugins().getFirst().active());
        var definition =
                assertInstanceOf(
                        ToolDocs.nonNullClass(RemoteToolDefinition.class),
                        top.focess.veto.util.Nullness.requireNonNull(
                                engine.resolveDefinition("plugin_text__length")));
        assertEquals(Danger.ELEVATED, definition.defaultDanger());
        assertTrue(engine.getActiveTools(Set.of()).stream().noneMatch(t -> t == definition));
        assertTrue(engine.getActiveTools(Set.of(definition.name())).contains(definition));
        var snapshot =
                top.focess.veto.agent.PluginContextSnapshot.from(
                        java.util.List.of(definition, definition), true);
        assertTrue(snapshot.lastRequest());
        assertEquals(1, snapshot.plugins().size());
        assertEquals("text", snapshot.plugins().getFirst().id());
        assertEquals("0.1.0", snapshot.plugins().getFirst().version());
        assertEquals(
                java.util.List.of("plugin_text__length"), snapshot.plugins().getFirst().tools());
        var agentOnlySnapshot =
                top.focess.veto.agent.PluginContextSnapshot.from(
                        engine.getActiveTools(Set.of()), false);
        assertEquals(1, agentOnlySnapshot.plugins().size());
        assertEquals("top.focess.builtin", agentOnlySnapshot.plugins().getFirst().id());
        assertEquals(
                java.util.List.of("create_group"), agentOnlySnapshot.plugins().getFirst().tools());
        var call = new ToolCall(definition.name(), Map.of("text", "a😀b"), "plugin-boot-call");
        assertEquals(ToolResultStatus.FAILURE, engine.execute(call, definition).status());
        UUID user = UUID.randomUUID();
        var session = new top.focess.veto.model.SessionEntity("test-owner", "plugin-selection");
        session.setPluginBindings(selection.selection(java.util.List.of("text")));
        sessions.saveAndFlush(session);
        UUID sessionId = UUID.fromString(session.getId());
        var permit =
                ToolExecutionPermit.capture(
                                call, definition, Workspace.single(STATE, PathMode.REAL))
                        .withCaller("test-agent", user, null, "test-owner", sessionId);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "test-agent",
                        user,
                        null,
                        "test-owner",
                        sessionId,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            var result = engine.execute(call, definition);
            assertEquals(
                    ToolResultStatus.SUCCESS,
                    result.status(),
                    result.content() + " interrupted=" + Thread.currentThread().isInterrupted());
            assertEquals("3", result.content());
            assertEquals(
                    ToolResultStatus.FAILURE,
                    engine.execute(
                                    new ToolCall(
                                            call.toolName(),
                                            Map.of("text", "different"),
                                            call.callId()),
                                    definition)
                            .status());
            var provenance = top.focess.veto.util.Nullness.requireNonNull(definition.provenance());
            var otherActivation =
                    new RemoteToolDefinition(
                            definition.name(),
                            definition.description(),
                            definition.serverName(),
                            definition.capability(),
                            definition.defaultDanger(),
                            definition.resultFormats(),
                            definition.inputSchema(),
                            new Provenance(
                                    provenance.pluginId(),
                                    "different-activation",
                                    provenance.pluginVersion()));
            assertFalse(
                    permit.authorizes(
                            call,
                            otherActivation,
                            top.focess.veto.util.Nullness.requireNonNull(
                                    ToolCallContextHolder.get())));
        } finally {
            ToolCallContextHolder.clear();
        }
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/plugins"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertFalse(response.body().contains("worker.mjs"));
    }

    @Test
    void untrustedConfigurationIsRejectedBeforeExecution() {
        assertThrows(
                IOException.class,
                () ->
                        new PluginManager(
                                "/unused",
                                node(),
                                false,
                                5000,
                                PluginTestSupport.providerOf(null)));
    }
}
