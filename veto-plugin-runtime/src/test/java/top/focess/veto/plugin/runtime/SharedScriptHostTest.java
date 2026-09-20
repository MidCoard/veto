package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedScriptHostTest {
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

    @Test
    void tenPluginsStartLazilyAndShareOneNodeProcess() throws Exception {
        try (var host = new ScriptHost(node(), 3000)) {
            for (int i = 0; i < 10; i++) host.register("plugin-" + i, () -> {});
            assertEquals(-1, host.processId());
            long pid = -1;
            for (int i = 0; i < 10; i++) {
                var result =
                        host.invoke(
                                "plugin-" + i,
                                Path.of("examples/text-tools/worker.mjs").toAbsolutePath(),
                                "text.length",
                                ScriptPlugin.JSON.createObjectNode().put("text", "a😀b"));
                assertEquals(3, result.asInt());
                if (pid < 0) pid = host.processId();
                else assertEquals(pid, host.processId());
            }
            host.unregister("plugin-0");
            assertEquals(
                    2,
                    host.invoke(
                                    "plugin-1",
                                    Path.of("examples/text-tools/worker.mjs").toAbsolutePath(),
                                    "text.length",
                                    ScriptPlugin.JSON.createObjectNode().put("text", "ab"))
                            .asInt());
            assertEquals(pid, host.processId());
        }
    }

    @Test
    void sharedHostTimeoutFailsAllRegisteredPlugins(@TempDir @NonNull Path root) throws Exception {
        Path script = root.resolve("blocked.mjs");
        Files.writeString(script, "while(true){};");
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try (var host = new ScriptHost(node(), 500)) {
            host.register("blocked", failures::incrementAndGet);
            host.register("peer", failures::incrementAndGet);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () ->
                            assertThrows(
                                    java.io.IOException.class,
                                    () ->
                                            host.invoke(
                                                    "blocked",
                                                    script,
                                                    "x",
                                                    ScriptPlugin.JSON.createObjectNode())));
            assertFalse(host.registered("peer"));
            assertEquals(2, failures.get());
        }
    }
}
