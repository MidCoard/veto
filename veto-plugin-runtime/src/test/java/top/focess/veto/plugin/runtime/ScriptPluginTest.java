package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.PluginFailure;

class ScriptPluginTest {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    private static @NonNull PluginContext context(@NonNull PluginIdentity identity) {
        return new PluginContext(
                identity,
                () -> {},
                () -> {
                    throw new IllegalStateException("Plugin context has no lifecycle owner");
                },
                Map.of());
    }

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

    /** Real worker plus host-owned lifecycle, matching application ownership. */
    private static @NonNull LoadedScript load(
            @NonNull Path root, @NonNull Path node, @NonNull Duration timeout) throws IOException {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ManagedPlugin managed = null;
        try {
            var script = new ScriptPluginLoader(node, timeout).load(root);
            managed = new ManagedPlugin(script, executor);
            managed.initialize(
                    context(script.identity()),
                    new top.focess.veto.api.plugin.contract.JsonValue.ObjectValue(Map.of()));
            managed.start();
            return new LoadedScript(script, managed, executor);
        } catch (Exception failure) {
            if (managed != null) managed.close();
            executor.shutdown();
            if (failure instanceof RuntimeException invalid) throw invalid;
            throw new IOException("Plugin activation failed", failure);
        }
    }

    private record LoadedScript(
            @NonNull ScriptPlugin script,
            @NonNull ManagedPlugin managed,
            java.util.concurrent.@NonNull ExecutorService executor)
            implements AutoCloseable {
        public void close() {
            managed.close();
            executor.shutdown();
        }

        java.util.@NonNull List<ScriptTool> tools() {
            return script.tools();
        }

        @NonNull String digest() {
            return script.digest();
        }

        boolean active() {
            return state() == top.focess.veto.api.plugin.PluginState.ACTIVE && script.active();
        }

        top.focess.veto.api.plugin.@NonNull PluginState state() {
            return managed.state();
        }

        <T extends @NonNull Object> @NonNull T execute(
                ManagedPlugin.@NonNull Operation<T> operation)
                throws top.focess.veto.api.plugin.contract.PluginFailure {
            return managed.execute(operation);
        }

        com.fasterxml.jackson.databind.@NonNull JsonNode invoke(
                @NonNull ScriptTool tool,
                com.fasterxml.jackson.databind.@NonNull JsonNode arguments)
                throws IOException {
            try {
                return managed.execute(
                        () -> {
                            try {
                                return script.invoke(tool, arguments);
                            } catch (IOException failure) {
                                throw new top.focess.veto.api.plugin.contract.PluginFailure(
                                        top.focess.veto.api.plugin.contract.PluginFailure.Code
                                                .INTERNAL_FAILURE);
                            }
                        });
            } catch (top.focess.veto.api.plugin.contract.PluginFailure failure) {
                throw new IOException("Plugin invocation failed", failure);
            }
        }
    }

    @Test
    void privateWorkerTimeoutDoesNotStopAnotherPackage(@TempDir @NonNull Path root)
            throws Exception {
        Path blockedRoot = Files.createDirectory(root.resolve("blocked"));
        Path healthyRoot = Files.createDirectory(root.resolve("healthy"));
        copy(blockedRoot);
        copy(healthyRoot);
        behavior(blockedRoot, "while (true) {}");
        try (var blocked = load(blockedRoot, node(), Duration.ofMillis(500));
                var healthy = load(healthyRoot, node(), Duration.ofSeconds(3))) {
            var arguments = JSON.createObjectNode().put("text", "abc");
            assertEquals(3, healthy.invoke(healthy.tools().getFirst(), arguments).asInt());
            assertThrows(
                    IOException.class, () -> blocked.invoke(blocked.tools().getFirst(), arguments));
            assertTrue(healthy.active());
            assertEquals(3, healthy.invoke(healthy.tools().getFirst(), arguments).asInt());
        }
    }

    @Test
    void closingOnePluginKeepsSharedManagerExecutorAvailable(@TempDir @NonNull Path root)
            throws Exception {
        Path firstDir = Files.createDirectory(root.resolve("first"));
        Path secondDir = Files.createDirectory(root.resolve("second"));
        copy(firstDir);
        copy(secondDir);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var loader = new ScriptPluginLoader(node(), Duration.ofSeconds(3));
        var first = new ManagedPlugin(loader.load(firstDir), executor);
        var secondScript = loader.load(secondDir);
        var second = new ManagedPlugin(secondScript, executor);
        try {
            for (var managed : java.util.List.of(first, second)) {
                managed.initialize(
                        context(managed.identity()),
                        new top.focess.veto.api.plugin.contract.JsonValue.ObjectValue(Map.of()));
                managed.start();
            }
            first.close();
            assertFalse(executor.isShutdown());
            assertEquals(
                    3,
                    second.execute(
                            () -> {
                                try {
                                    return secondScript
                                            .invoke(
                                                    secondScript.tools().getFirst(),
                                                    JSON.createObjectNode().put("text", "a😀b"))
                                            .asInt();
                                } catch (IOException failure) {
                                    throw new top.focess.veto.api.plugin.contract.PluginFailure(
                                            top.focess.veto.api.plugin.contract.PluginFailure.Code
                                                    .INTERNAL_FAILURE);
                                }
                            }));
        } finally {
            first.close();
            second.close();
            executor.shutdown();
        }
    }

    @Test
    void closeDrainsAdmittedCallsAndRejectsNewCalls(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        var plugin = load(root, node(), Duration.ofSeconds(3));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Boolean>();
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                java.util.concurrent.Future<Integer> call =
                        callers.<Integer>submit(
                                () ->
                                        plugin.execute(
                                                () -> {
                                                    int result;
                                                    try {
                                                        result =
                                                                plugin.invoke(
                                                                                plugin.tools()
                                                                                        .getFirst(),
                                                                                JSON.createObjectNode()
                                                                                        .put(
                                                                                                "text",
                                                                                                "a😀b"))
                                                                        .asInt();
                                                    } catch (IOException failure) {
                                                        throw new PluginFailure(
                                                                PluginFailure.Code
                                                                        .INTERNAL_FAILURE);
                                                    }
                                                    // A plugin must not synchronously close itself
                                                    // while owning an invocation.
                                                    assertThrows(
                                                            IllegalStateException.class,
                                                            plugin::close);
                                                    entered.countDown();
                                                    release.join();
                                                    return result;
                                                }));
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(call.isDone());
                assertEquals(
                        4,
                        plugin.invoke(
                                        plugin.tools().getFirst(),
                                        JSON.createObjectNode().put("text", "中文测试"))
                                .asInt());
                var closing = callers.submit(plugin::close);
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (plugin.state() != top.focess.veto.api.plugin.PluginState.STOPPING
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(top.focess.veto.api.plugin.PluginState.STOPPING, plugin.state());
                assertFalse(closing.isDone());
                assertThrows(
                        top.focess.veto.api.plugin.contract.PluginFailure.class,
                        () -> plugin.execute(() -> true));
                release.complete(true);
                assertEquals(
                        Integer.valueOf(3), call.get(5, java.util.concurrent.TimeUnit.SECONDS));
                closing.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(top.focess.veto.api.plugin.PluginState.CLOSED, plugin.state());
                plugin.close();
                assertThrows(
                        top.focess.veto.api.plugin.contract.PluginFailure.class,
                        plugin.managed()::start);
                assertEquals(top.focess.veto.api.plugin.PluginState.CLOSED, plugin.state());
            } finally {
                release.complete(true);
            }
        } finally {
            plugin.close();
        }
    }

    @Test
    void invokesPersistentWorkerAndPinsCode(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        var plugin = load(root, node(), Duration.ofSeconds(3));
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
        try (var plugin = load(root, node(), Duration.ofSeconds(3))) {
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
                    () -> load(root, node(), Duration.ofSeconds(3)));
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
        assertThrows(IOException.class, () -> load(root, node(), Duration.ofSeconds(3)));
    }

    @Test
    void startupTimeoutDoesNotWaitForItsOwnCleanupQueue(@TempDir @NonNull Path root)
            throws Exception {
        copy(root);
        Files.writeString(root.resolve("worker.mjs"), "setInterval(() => {}, 1000);");
        assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () ->
                        assertThrows(
                                IOException.class,
                                () -> {
                                    try (var plugin = load(root, node(), Duration.ofMillis(500))) {
                                        plugin.invoke(
                                                plugin.tools().getFirst(),
                                                JSON.createObjectNode().put("text", "test"));
                                    }
                                }));
    }

    @Test
    void deadlineKillsWorkerAndDoesNotLeakPayload(@TempDir @NonNull Path root) throws Exception {
        copy(root);
        behavior(root, "setInterval(() => {}, 1000); await new Promise(() => {});");
        try (var plugin = load(root, node(), Duration.ofMillis(500))) {
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
        try (var plugin = load(root, node(), Duration.ofSeconds(3))) {
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
        try (var plugin = load(root, node(), Duration.ofSeconds(3))) {
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
                                "id: request.method === 'invoke' ? request.id + 1 : request.id,"
                                        + " result"));
        try (var plugin = load(root, node(), Duration.ofSeconds(3))) {
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
                "result = ['PATH', 'NODE_OPTIONS', 'VETO_PLUGIN_TEST_SENTINEL'].filter(key =>"
                        + " Object.hasOwn(process.env, key)).length;");
        try (var plugin = load(root, node(), Duration.ofSeconds(3))) {
            assertEquals(
                    0,
                    plugin.invoke(
                                    plugin.tools().getFirst(),
                                    JSON.createObjectNode().put("text", "x"))
                            .asInt());
        }
    }
}
