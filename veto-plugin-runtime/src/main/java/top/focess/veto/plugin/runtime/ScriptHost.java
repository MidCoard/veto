package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.*;

/** One lazy Node process per manager. Trusted plugins share a heap; this is not a sandbox. */
public final class ScriptHost implements AutoCloseable {
    private final @NonNull Path node;
    private final long timeoutMillis;
    private final @NonNull ReentrantLock lock = new ReentrantLock();
    private final @NonNull Map<String, Runnable> registrations = new ConcurrentHashMap<>();
    private volatile @Nullable Process process;
    private @Nullable Path hostFile;
    private long sequence;

    public ScriptHost(@NonNull Path node, long timeoutMillis) {
        this.node = node;
        this.timeoutMillis = timeoutMillis;
    }

    public void register(@NonNull String key, @NonNull Runnable failure) {
        registrations.put(key, failure);
    }

    public boolean registered(@NonNull String key) {
        return registrations.containsKey(key);
    }

    public long processId() {
        var current = process;
        return current == null ? -1 : current.pid();
    }

    private void ensureStarted() throws IOException {
        if (process != null) {
            if (!process.isAlive()) throw new IOException("Script host stopped");
            return;
        }
        var resource = ScriptHost.class.getResourceAsStream("script-host.mjs");
        if (resource == null) throw new IOException("Missing script host");
        Path file = Files.createTempFile("veto-script-host-", ".mjs");
        hostFile = file;
        try (resource) {
            Files.copy(resource, file, StandardCopyOption.REPLACE_EXISTING);
        }
        var builder =
                new ProcessBuilder(node.toString(), "--experimental-vm-modules", file.toString());
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();
        process.onExit().thenRun(this::failAll);
    }

    private void failAll() {
        registrations.forEach(
                (key, listener) -> {
                    if (registrations.remove(key, listener)) listener.run();
                });
    }

    public @NonNull JsonNode invoke(
            @NonNull String key,
            @NonNull Path entry,
            @NonNull String handler,
            @NonNull JsonNode arguments)
            throws IOException {
        if (!registered(key)) throw new IOException("Plugin not registered");
        return exchange(
                "invoke",
                ScriptPlugin.JSON
                        .createObjectNode()
                        .put("plugin", key)
                        .put("entryPoint", entry.toString())
                        .put("handler", handler)
                        .set("arguments", arguments));
    }

    public void unregister(@NonNull String key) {
        if (registrations.remove(key) == null) return;
        var current = process;
        if (current != null && current.isAlive())
            try {
                exchange("unload", ScriptPlugin.JSON.createObjectNode().put("plugin", key));
            } catch (IOException ignored) {
            }
    }

    @Override
    public void close() {
        registrations.clear();
        var current = process;
        if (current != null) terminate(current);
        var file = hostFile;
        if (file != null)
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
    }

    private static void terminate(@NonNull Process worker) {
        worker.descendants().forEach(ProcessHandle::destroyForcibly);
        worker.destroyForcibly();
        try {
            worker.getInputStream().close();
            worker.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    private @NonNull JsonNode exchange(@NonNull String method, @NonNull JsonNode params)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("Plugin is busy");
            ensureStarted();
            Process worker = process;
            if (worker == null || !worker.isAlive()) throw new IOException("Plugin is unavailable");
            long requestId = ++sequence;
            byte[] request =
                    ScriptPlugin.JSON.writeValueAsBytes(
                            ScriptPlugin.JSON
                                    .createObjectNode()
                                    .put("jsonrpc", "2.0")
                                    .put("id", requestId)
                                    .put("method", method)
                                    .set("params", params));
            if (request.length > ScriptPlugin.MAX_FRAME)
                throw new IOException("Plugin request exceeds limit");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future =
                        executor.submit(
                                () -> {
                                    worker.getOutputStream().write(request);
                                    worker.getOutputStream().write('\n');
                                    worker.getOutputStream().flush();
                                    var bytes = new ByteArrayOutputStream();
                                    while (true) {
                                        int b = worker.getInputStream().read();
                                        if (b < 0)
                                            throw new IOException("Plugin closed its channel");
                                        if (b == '\n') break;
                                        if (bytes.size() >= ScriptPlugin.MAX_FRAME)
                                            throw new IOException("Plugin response exceeds limit");
                                        bytes.write(b);
                                    }
                                    JsonNode response = ScriptPlugin.parse(bytes.toByteArray());
                                    PluginSchema.fields(
                                            response, Set.of("jsonrpc", "id", "result", "error"));
                                    PluginSchema.require(
                                            response.path("jsonrpc").asText().equals("2.0")
                                                    && response.path("id").isIntegralNumber()
                                                    && response.path("id").canConvertToLong()
                                                    && response.path("id").asLong() == requestId
                                                    && response.has("result")
                                                    && !response.has("error"));
                                    return response.path("result");
                                });
                try {
                    return future.get(
                            Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (Exception e) {
                    // Abort transport I/O before waiting for its worker to exit. Lifecycle cleanup
                    // is queued separately and may be waiting for this startup hook to return.
                    terminate(worker);
                    failAll();
                    future.cancel(true);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IOException("Plugin invocation failed");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Plugin invocation cancelled");
        } finally {
            if (acquired) lock.unlock();
        }
    }
}
