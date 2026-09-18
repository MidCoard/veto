package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptPluginTest {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    private static @NonNull Path node() {
        for (String part :
                Objects.requireNonNullElse(System.getenv("PATH"), "").split(File.pathSeparator)) {
            Path candidate =
                    Path.of(
                            part,
                            System.getProperty("os.name", "").startsWith("Windows")
                                    ? "node.exe"
                                    : "node");
            if (Files.isExecutable(candidate)) return candidate.toAbsolutePath();
        }
        throw new IllegalStateException("Node.js is required for script-plugin tests");
    }

    private static void copy(@NonNull Path root) throws IOException {
        for (String file : new String[] {"plugin.json", "worker.mjs"})
            Files.copy(Path.of("examples/text-tools", file), root.resolve(file));
    }

    private static void behavior(@NonNull Path root, @NonNull String expression)
            throws IOException {
        Path script = root.resolve("worker.mjs");
        Files.writeString(
                script,
                Files.readString(script)
                        .replace(
                                "result = [...request.params.arguments.text].length;", expression));
    }

    @Test
    void invokesPersistentWorkerAndPinsCode(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3));
        try {
            var tool = plugin.tools().getFirst();
            assertEquals(
                    3, plugin.invoke(tool, JSON.createObjectNode().put("text", "a😀b")).asInt());
            Files.writeString(root.resolve("worker.mjs"), "throw new Error('changed');");
            assertEquals(0, plugin.invoke(tool, JSON.createObjectNode().put("text", "")).asInt());
            assertTrue(plugin.active());
            assertEquals(64, plugin.digest().length());
            var forged =
                    new ScriptTool(
                            tool.id(),
                            tool.description(),
                            tool.handler(),
                            tool.inputSchema(),
                            tool.outputSchema());
            assertThrows(
                    IOException.class,
                    () -> plugin.invoke(forged, JSON.createObjectNode().put("text", "x")));
            plugin.close();
            assertFalse(plugin.active());
            assertThrows(
                    IOException.class,
                    () -> plugin.invoke(tool, JSON.createObjectNode().put("text", "x")));
        } finally {
            plugin.close();
        }
    }

    @Test
    void invalidArgumentsDoNotReachOrRetireWorker(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3))) {
            var tool = plugin.tools().getFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> plugin.invoke(tool, JSON.createObjectNode().put("text", 42)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            plugin.invoke(
                                    tool,
                                    JSON.createObjectNode().put("text", "x").put("extra", true)));
            assertEquals(1, plugin.invoke(tool, JSON.createObjectNode().put("text", "x")).asInt());
        }
    }

    @Test
    void unsupportedManifestAndSchemasFailBeforeStart(@TempDir @NonNull Path root)
            throws Exception {
        copy(root);
        Path file = root.resolve("plugin.json");
        String original = Files.readString(file);
        for (String invalid :
                new String[] {
                    original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"hooks\": []"),
                    original.replace(
                            "\"type\": \"string\"", "\"type\": \"string\", \"pattern\": \".*\""),
                    original.replace("worker.mjs", "../worker.mjs"),
                    original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 4294967297")
                }) {
            Files.writeString(file, invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ScriptPlugin.load(root, node(), Duration.ofSeconds(3)));
        }
    }

    @Test
    void duplicateJsonFieldsAreRejected(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        Path file = root.resolve("plugin.json");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace(
                                "\"schemaVersion\": 1",
                                "\"schemaVersion\": 1, \"schemaVersion\": 1"));
        assertThrows(
                IOException.class, () -> ScriptPlugin.load(root, node(), Duration.ofSeconds(3)));
    }

    @Test
    void deadlineKillsWorkerAndDoesNotLeakPayload(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        behavior(root, "setInterval(() => {}, 1000); await new Promise(() => {});");
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofMillis(500))) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () -> {
                        var error =
                                assertThrows(
                                        IOException.class,
                                        () ->
                                                plugin.invoke(
                                                        plugin.tools().getFirst(),
                                                        JSON.createObjectNode()
                                                                .put("text", "CANARY_PRIVATE")));
                        assertFalse(error.toString().contains("CANARY_PRIVATE"));
                        assertFalse(plugin.active());
                    });
        }
    }

    @Test
    void oversizedOutputRetiresWorker(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        behavior(root, "result = 'x'.repeat(70000);");
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3))) {
            assertThrows(
                    IOException.class,
                    () ->
                            plugin.invoke(
                                    plugin.tools().getFirst(),
                                    JSON.createObjectNode().put("text", "x")));
            assertFalse(plugin.active());
        }
    }

    @Test
    void invalidResultRetiresWorker(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        behavior(root, "result = 'CANARY_PRIVATE';");
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3))) {
            var error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    plugin.invoke(
                                            plugin.tools().getFirst(),
                                            JSON.createObjectNode().put("text", "x")));
            assertFalse(error.toString().contains("CANARY_PRIVATE"));
            assertFalse(plugin.active());
        }
    }

    @Test
    void wrongResponseIdentityRetiresWorker(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        Path file = root.resolve("worker.mjs");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace(
                                "id: request.id, result",
                                "id: request.method === 'invoke' ? request.id + 1 : request.id, result"));
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3))) {
            assertThrows(
                    IOException.class,
                    () ->
                            plugin.invoke(
                                    plugin.tools().getFirst(),
                                    JSON.createObjectNode().put("text", "x")));
            assertFalse(plugin.active());
        }
    }

    @Test
    void inheritedEnvironmentIsNotExposed(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        behavior(
                root,
                "result = ['PATH', 'NODE_OPTIONS', 'VETO_PLUGIN_TEST_SENTINEL'].filter(key => Object.hasOwn(process.env, key)).length;");
        try (var plugin = ScriptPlugin.load(root, node(), Duration.ofSeconds(3))) {
            assertEquals(
                    0,
                    plugin.invoke(
                                    plugin.tools().getFirst(),
                                    JSON.createObjectNode().put("text", "x"))
                            .asInt());
        }
    }
}
