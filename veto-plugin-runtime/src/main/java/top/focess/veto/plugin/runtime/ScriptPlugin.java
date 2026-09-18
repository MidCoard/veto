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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.ExtensionCatalog;
import top.focess.veto.extension.ExtensionContribution;
import top.focess.veto.extension.ExtensionId;
import top.focess.veto.extension.ExtensionPoint;
import top.focess.veto.extension.ExtensionSource;

/** Operator-trusted local code, not a sandbox. Only tools are supported in protocol v1. */
public final class ScriptPlugin implements AutoCloseable {
    public static final int MAX_FRAME = 65_536;
    public static final @NonNull ExtensionPoint<ScriptTool> TOOLS =
            new ExtensionPoint<>(
                    new ExtensionId("veto:script-tools"),
                    1,
                    ScriptTool.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    private static final @NonNull ObjectMapper JSON =
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
    private final @NonNull ExtensionCatalog catalog;
    private final @NonNull Path snapshot;
    private final @NonNull Process process;
    private final long timeoutMillis;
    private final @NonNull ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;
    private long sequence;
    private final @NonNull String activationId = java.util.UUID.randomUUID().toString();

    public @NonNull String bindingId() {
        return "plugin:" + id + ":" + digest + ":" + activationId;
    }

    private ScriptPlugin(
            @NonNull String id,
            @NonNull String version,
            @NonNull String digest,
            @NonNull ExtensionCatalog catalog,
            @NonNull Path snapshot,
            @NonNull Process process,
            long timeoutMillis) {
        this.id = id;
        this.version = version;
        this.digest = digest;
        this.catalog = catalog;
        this.snapshot = snapshot;
        this.process = process;
        this.timeoutMillis = timeoutMillis;
    }

    public static @NonNull ScriptPlugin load(
            @NonNull Path directory, @NonNull Path node, @NonNull Duration timeout)
            throws IOException {
        PluginSchema.require(node.isAbsolute() && Files.isExecutable(node));
        long millis = timeout.toMillis();
        PluginSchema.require(millis >= 100 && millis <= 60_000);
        Path root = directory.toRealPath();
        byte[] manifestBytes = readFile(root.resolve("plugin.json"));
        JsonNode manifest = parse(manifestBytes);
        PluginSchema.fields(
                manifest, Set.of("schemaVersion", "id", "version", "entryPoint", "tools"));
        PluginSchema.require(
                manifest.path("schemaVersion").isIntegralNumber()
                        && manifest.path("schemaVersion").canConvertToInt()
                        && manifest.path("schemaVersion").asInt() == 1);
        String id = text(manifest, "id");
        PluginSchema.require(id.matches("[a-z][a-z0-9_]{0,19}"));
        String version = text(manifest, "version");
        PluginSchema.require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        String entry = text(manifest, "entryPoint");
        PluginSchema.require(entry.matches("[a-zA-Z0-9_-]+\\.mjs"));
        byte[] script = readFile(root.resolve(entry));
        PluginSchema.require(
                manifest.path("tools").isArray()
                        && manifest.path("tools").size() >= 1
                        && manifest.path("tools").size() <= 32);
        List<ExtensionContribution<?>> contributions = new ArrayList<>();
        for (JsonNode tool : manifest.path("tools")) {
            PluginSchema.fields(
                    tool, Set.of("id", "description", "handler", "inputSchema", "outputSchema"));
            var descriptor =
                    new ScriptTool(
                            text(tool, "id"),
                            text(tool, "description"),
                            text(tool, "handler"),
                            tool.path("inputSchema"),
                            tool.path("outputSchema"));
            contributions.add(ExtensionContribution.of(TOOLS, descriptor.id(), descriptor));
        }
        ExtensionCatalog catalog =
                new ExtensionCatalog.Builder()
                        .define(TOOLS, ignored -> {})
                        .stage(
                                new ExtensionSource(id, version, ExtensionSource.Origin.PLUGIN),
                                contributions)
                        .freeze();
        String digest;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(manifestBytes);
            hash.update((byte) 0);
            digest = HexFormat.of().formatHex(hash.digest(script));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path snapshot = Files.createTempDirectory("veto-plugin-");
        Process process = null;
        try {
            Path executable = snapshot.resolve(entry);
            Files.write(executable, script);
            var builder = new ProcessBuilder(node.toString(), executable.toString());
            builder.directory(snapshot.toFile());
            // Never inherit NODE_OPTIONS or provider/vault credentials from the host environment.
            builder.environment().clear();
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            ScriptPlugin plugin =
                    new ScriptPlugin(id, version, digest, catalog, snapshot, process, millis);
            JsonNode hello =
                    plugin.exchange(
                            "initialize", JSON.createObjectNode().put("protocolVersion", 1));
            PluginSchema.require(
                    hello.path("protocolVersion").isIntegralNumber()
                            && hello.path("protocolVersion").canConvertToInt()
                            && hello.path("protocolVersion").asInt() == 1);
            return plugin;
        } catch (Exception e) {
            if (process != null) terminate(process);
            removeSnapshot(snapshot);
            throw new IOException(
                    "Plugin startup failed",
                    e instanceof IllegalArgumentException
                            ? null
                            : new IOException(e.getClass().getSimpleName()));
        }
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
        return !closed && process.isAlive();
    }

    public @NonNull List<ScriptTool> tools() {
        return catalog.entries(TOOLS).stream().map(entry -> entry.implementation()).toList();
    }

    public @NonNull JsonNode invoke(@NonNull ScriptTool tool, @NonNull JsonNode arguments)
            throws IOException {
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
            close();
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
            if (!active()) throw new IOException("Plugin is unavailable");
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
                                    process.getOutputStream().write(request);
                                    process.getOutputStream().write('\n');
                                    process.getOutputStream().flush();
                                    var bytes = new ByteArrayOutputStream();
                                    while (true) {
                                        int b = process.getInputStream().read();
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
                    close();
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

    private static byte @NonNull [] readFile(@NonNull Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Plugin file is missing or symbolic");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FRAME + 1);
            if (bytes.length > MAX_FRAME) throw new IOException("Plugin file exceeds limit");
            return bytes;
        }
    }

    private static @NonNull JsonNode parse(byte @NonNull [] bytes) throws IOException {
        JsonNode result = JSON.readTree(bytes);
        if (result == null) throw new IOException("Empty plugin message");
        return result;
    }

    private static @NonNull String text(@NonNull JsonNode node, @NonNull String key) {
        PluginSchema.require(node.path(key).isTextual());
        return node.path(key).asText();
    }

    @Override
    public void close() {
        closed = true;
        terminate(process);
        removeSnapshot(snapshot);
    }

    private static void terminate(@NonNull Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.getInputStream().close();
            process.getOutputStream().close();
        } catch (IOException ignored) {
            /* Closed channel. */
        }
    }

    private static void removeSnapshot(@NonNull Path snapshot) {
        try (var files = Files.list(snapshot)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
            Files.deleteIfExists(snapshot);
        } catch (IOException ignored) {
            /* Best-effort removal; snapshot contains no invocation data. */
        }
    }
}
