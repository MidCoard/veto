package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.*;

/**
 * One lazy Node process per manager. Trusted plugins share a heap; this is not a sandbox.
 *
 * <p>Threading: one reentrant lock serializes process startup, framed request/response exchanges,
 * sequence allocation, registration and close. Close waits for the active exchange, then rejects
 * later registration and queued invocations. Concurrent registration lookups and process-ID reads
 * are snapshots, not invocation admission. Unregistration removes its key before best-effort
 * unload; invocation rechecks the key under the exchange lock before contacting Node.
 *
 * <p>Failure listeners may run on the process-exit completion thread or on the failing exchange
 * caller while the exchange lock is held. They must only signal failure and must not wait for
 * lifecycle cleanup or another exchange. Ordinary listener failures do not skip other listeners; a
 * fatal JVM error is propagated after all remaining listeners are attempted.
 */
public final class ScriptHost implements AutoCloseable {
    private final @NonNull Path node;
    private static final @NonNull Logger log =
            System.getLogger("top.focess.veto.plugin.runtime.ScriptHost");
    private final long timeoutMillis;
    private final @NonNull ReentrantLock lock = new ReentrantLock();
    private final @NonNull Map<String, Runnable> registrations = new ConcurrentHashMap<>();
    private volatile Process process;
    private Path hostFile;
    private long sequence;
    private boolean closed;

    /** Creates a host that will lazily launch the given Node binary with a per-call timeout. */
    public ScriptHost(@NonNull Path node, long timeoutMillis) {
        this.node = node;
        this.timeoutMillis = timeoutMillis;
    }

    /** Registers a plugin key and the failure listener invoked if the shared process dies. */
    public void register(@NonNull String key, @NonNull Runnable failure) {
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("Script host is closed");
            registrations.put(key, failure);
        } finally {
            lock.unlock();
        }
    }

    /** Reports whether the given plugin key is currently registered with this host. */
    public boolean registered(@NonNull String key) {
        return registrations.containsKey(key);
    }

    /** Returns the OS process id of the shared worker, or {@code -1} if it is not running. */
    public long processId() {
        var current = process;
        return current == null ? -1 : current.pid();
    }

    private void ensureStarted() throws IOException {
        var current = process;
        if (current != null) {
            if (!current.isAlive()) throw new IOException("Script host stopped");
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
        var started = builder.start();
        process = started;
        started.onExit().thenRun(this::failAll);
    }

    private void failAll() {
        Error fatal = null;
        for (var entry : registrations.entrySet()) {
            if (!registrations.remove(entry.getKey(), entry.getValue())) continue;
            try {
                entry.getValue().run();
            } catch (Throwable failure) {
                fatal = preserveFatal(fatal, failure);
                if (fatal == null)
                    log.log(
                            Level.WARNING,
                            "Script failure listener failed: {0}",
                            failure.getClass().getSimpleName());
            }
        }
        if (fatal != null) throw fatal;
    }

    // Preserve the legacy fatal signal while the JDK still supports it.
    @SuppressWarnings("removal")
    private static Error preserveFatal(Error fatal, @NonNull Throwable failure) {
        if (fatal != null) return fatal;
        return failure instanceof Error error
                        && (error instanceof VirtualMachineError || error instanceof ThreadDeath)
                ? error
                : null;
    }

    /** Invokes a registered plugin's handler in the shared process and returns its JSON result. */
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

    /** Unregisters a plugin key and best-effort unloads it from the running process. */
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
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            registrations.clear();
            var current = process;
            if (current != null) terminate(current);
            var file = hostFile;
            if (file != null)
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
        } finally {
            lock.unlock();
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
        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("Plugin is busy");
            if (closed) throw new IOException("Script host is closed");
            if (method.equals("invoke") && !registered(params.path("plugin").asText()))
                throw new IOException("Plugin not registered");
            // Measure the invocation budget only after admission, so waiting for a busy host does
            // not consume the time available to actually talk to it.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
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
