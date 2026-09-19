package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.ExtensionContribution;
import top.focess.veto.extension.contract.JsonValue;
import top.focess.veto.plugin.api.AbstractVetoPlugin;
import top.focess.veto.plugin.api.PluginContext;
import top.focess.veto.plugin.api.PluginContributions;
import top.focess.veto.plugin.api.PluginIdentity;

/** Operator-trusted local code, not a sandbox. Only tools are supported in protocol v1. */
public final class ScriptPlugin extends AbstractVetoPlugin {
    public static final int MAX_FRAME = 65_536;
    static final @NonNull ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(32)
                                                    .maxStringLength(MAX_FRAME)
                                                    .maxNumberLength(128)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final @NonNull String id;
    private final @NonNull String version;
    private final @NonNull String digest;
    private final @NonNull List<ScriptTool> descriptors;
    private final @NonNull Path snapshot;
    private @org.jspecify.annotations.Nullable Process process;
    private final @NonNull Path node;
    private final @NonNull Path executable;
    private final long timeoutMillis;
    private final @NonNull ReentrantLock lock = new ReentrantLock();
    private long sequence;
    private @NonNull Runnable failureReporter = () -> {};

    ScriptPlugin(
            @NonNull String id,
            @NonNull String version,
            @NonNull String digest,
            @NonNull List<ScriptTool> descriptors,
            @NonNull Path snapshot,
            @NonNull Path node,
            @NonNull Path executable,
            long timeoutMillis) {
        this.id = id;
        this.version = version;
        this.digest = digest;
        this.descriptors = List.copyOf(descriptors);
        this.snapshot = snapshot;
        this.node = node;
        this.executable = executable;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        failureReporter = context::reportFailure;
        PluginSchema.require(configuration.values().isEmpty());
        return new PluginContributions(
                tools().stream()
                        .<ExtensionContribution<?>>map(
                                tool ->
                                        ExtensionContribution.of(
                                                top.focess.veto.extension.contract
                                                        .StandardExtensionPoints.TOOLS,
                                                tool.id(),
                                                new top.focess.veto.extension.contract
                                                        .ToolContribution(
                                                        tool.description(),
                                                        PluginJson.object(tool.inputSchema()),
                                                        PluginJson.object(tool.outputSchema()),
                                                        top.focess.veto.extension.contract
                                                                .ToolContribution.Effect
                                                                .EXTERNAL_UNKNOWN,
                                                        java.util.Set.of(),
                                                        (arguments, cancellation) -> {
                                                            cancellation.checkCancelled();
                                                            try {
                                                                var result =
                                                                        invoke(
                                                                                tool,
                                                                                PluginJson.toNode(
                                                                                        arguments));
                                                                cancellation.checkCancelled();
                                                                return PluginJson.fromNode(result);
                                                            } catch (IOException failure) {
                                                                throw new top.focess.veto.extension
                                                                        .contract.ExtensionFailure(
                                                                        top.focess.veto.extension
                                                                                .contract
                                                                                .ExtensionFailure
                                                                                .Code
                                                                                .INTERNAL_FAILURE);
                                                            }
                                                        })))
                        .toList());
    }

    @Override
    protected void onStart() throws IOException {
        var builder = new ProcessBuilder(node.toString(), executable.toString());
        builder.directory(snapshot.toFile());
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();
        process.onExit().thenRun(() -> failureReporter.run());
        JsonNode hello = exchange("initialize", JSON.createObjectNode().put("protocolVersion", 1));
        PluginSchema.require(
                hello.path("protocolVersion").isIntegralNumber()
                        && hello.path("protocolVersion").asInt() == 1);
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity(id, version);
    }

    public @NonNull String id() {
        return id;
    }

    public @NonNull String version() {
        return version;
    }

    public @NonNull String digest() {
        return digest;
    }

    public boolean active() {
        return process != null && process.isAlive();
    }

    public @NonNull List<ScriptTool> tools() {
        return descriptors;
    }

    @NonNull JsonNode invoke(@NonNull ScriptTool tool, @NonNull JsonNode arguments)
            throws IOException {
        if (!active()) throw new IOException("Plugin is unavailable");
        if (tools().stream().noneMatch(registered -> registered == tool))
            throw new IOException("Unknown plugin tool");
        PluginSchema.validate(tool.inputSchema(), arguments);
        JsonNode result =
                exchange(
                        "invoke",
                        JSON.createObjectNode()
                                .put("handler", tool.handler())
                                .set("arguments", arguments));
        try {
            PluginSchema.validate(tool.outputSchema(), result);
        } catch (IllegalArgumentException e) {
            failureReporter.run();
            throw new IOException("Invalid plugin result");
        }
        return result;
    }

    private @NonNull JsonNode exchange(@NonNull String method, @NonNull JsonNode params)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("Plugin is busy");
            Process worker = process;
            if (worker == null || !worker.isAlive()) throw new IOException("Plugin is unavailable");
            long requestId = ++sequence;
            byte[] request =
                    JSON.writeValueAsBytes(
                            JSON.createObjectNode()
                                    .put("jsonrpc", "2.0")
                                    .put("id", requestId)
                                    .put("method", method)
                                    .set("params", params));
            if (request.length > MAX_FRAME) throw new IOException("Plugin request exceeds limit");
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
                                        if (bytes.size() >= MAX_FRAME)
                                            throw new IOException("Plugin response exceeds limit");
                                        bytes.write(b);
                                    }
                                    JsonNode response = parse(bytes.toByteArray());
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
                    failureReporter.run();
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

    static byte @NonNull [] readFile(@NonNull Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Plugin file is missing or symbolic");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FRAME + 1);
            if (bytes.length > MAX_FRAME) throw new IOException("Plugin file exceeds limit");
            return bytes;
        }
    }

    static @NonNull JsonNode parse(byte @NonNull [] bytes) throws IOException {
        JsonNode result = JSON.readTree(bytes);
        if (result == null) throw new IOException("Empty plugin message");
        return result;
    }

    static @NonNull String text(@NonNull JsonNode node, @NonNull String key) {
        PluginSchema.require(node.path(key).isTextual());
        return node.path(key).asText();
    }

    @Override
    protected void onClose() {
        Process worker = process;
        if (worker != null) terminate(worker);
        removeSnapshot(snapshot);
    }

    private static void terminate(@NonNull Process worker) {
        worker.descendants().forEach(ProcessHandle::destroyForcibly);
        worker.destroyForcibly();
        try {
            worker.getInputStream().close();
            worker.getOutputStream().close();
        } catch (IOException ignored) {
            /* Closed channel. */
        }
    }

    static void removeSnapshot(@NonNull Path snapshot) {
        try (var files = Files.list(snapshot)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
            Files.deleteIfExists(snapshot);
        } catch (IOException ignored) {
            /* Best-effort removal; snapshot contains no invocation data. */
        }
    }
}
