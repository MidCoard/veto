package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        var failures = new AtomicInteger();
        try (var host = new ScriptHost(node(), 500)) {
            host.register("blocked", failures::incrementAndGet);
            host.register("peer", failures::incrementAndGet);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () ->
                            assertThrows(
                                    IOException.class,
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

    @Test
    void canceledTimersLeaveTheRegistryAndUnloadClearsRemainingTimers(@TempDir @NonNull Path root)
            throws Exception {
        String source;
        try (var resource = ScriptHost.class.getResourceAsStream("script-host.mjs")) {
            if (resource == null) throw new AssertionError("Missing script host");
            source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Observe the real registry only in this test-owned host copy, without a runtime hook.
        int main =
                source.indexOf("for await (const line of createInterface({input:process.stdin}))");
        assertTrue(main > 0);
        Path host = root.resolve("timer-host.mjs");
        Files.writeString(
                host,
                source.substring(0, main)
                                .replace(
                                        "const timers = new Set();",
                                        "const timers = globalThis.timerRegistry = new Set();")
                                .replace(
                                        "vm.createContext({Buffer,",
                                        "vm.createContext({timerCount:()=>timers.size, Buffer,")
                        + """
                        const {default:assert} = await import('node:assert/strict');
                        const plugin = await load('timers', process.argv[2]);
                        assert.equal(await plugin.request('invoke', {handler:'text.length', arguments:{text:''}}), 0);
                        assert.equal(globalThis.timerRegistry.size, 2);
                        plugin.dispose();
                        assert.equal(globalThis.timerRegistry.size, 0);
                        """);
        Path plugin = root.resolve("timers.mjs");
        Files.writeString(
                plugin,
                Files.readString(Path.of("examples/text-tools/worker.mjs"))
                        .replace(
                                "result = [...request.params.arguments.text].length;",
                                """
                                let fired = 0;
                                const callback = () => fired++;
                                for (let i = 0; i < 100; i++) {
                                  clearTimeout(setTimeout(callback, 10));
                                  clearInterval(setInterval(callback, 10));
                                  clearInterval(+setTimeout(callback, 10));
                                  clearTimeout(+setInterval(callback, 10));
                                  clearTimeout(String(+setTimeout(callback, 10)));
                                  clearInterval(String(+setInterval(callback, 10)));
                                }
                                clearTimeout(null); clearInterval(undefined); clearTimeout('unknown');
                                await new Promise(resolve => setTimeout(resolve, 30));
                                if (fired !== 0) throw new Error('Canceled timer fired');
                                result = timerCount();
                                setTimeout(callback, 60000);
                                setInterval(callback, 60000);
                                """));
        Path output = root.resolve("timer-host.log");
        var worker =
                new ProcessBuilder(
                                node().toString(),
                                "--experimental-vm-modules",
                                host.toString(),
                                plugin.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            worker.getOutputStream().close();
            assertTrue(
                    worker.waitFor(10, TimeUnit.SECONDS), "Timer host did not stop after unload");
            assertEquals(0, worker.exitValue(), Files.readString(output));
        } finally {
            if (worker.isAlive()) assertSame(worker, worker.destroyForcibly());
            assertTrue(worker.waitFor(5, TimeUnit.SECONDS), "Timer host did not exit");
        }
    }
}
