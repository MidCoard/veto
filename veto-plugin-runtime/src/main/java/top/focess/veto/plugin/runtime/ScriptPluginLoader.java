package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Validates and snapshots a package without executing it; lifecycle activation belongs to the host.
 */
public final class ScriptPluginLoader implements PluginLoader<ScriptPlugin> {
    private final @NonNull Path node;
    private final @org.jspecify.annotations.Nullable ScriptHost host;
    private final @NonNull Duration timeout;

    public ScriptPluginLoader(@NonNull Path node, @NonNull Duration timeout) {
        this(node, timeout, null);
    }

    public ScriptPluginLoader(
            @NonNull Path node,
            @NonNull Duration timeout,
            @org.jspecify.annotations.Nullable ScriptHost host) {
        this.host = host;
        this.node = node;
        this.timeout = timeout;
    }

    public @NonNull ScriptPlugin load(@NonNull Path directory) throws IOException {
        PluginSchema.require(node.isAbsolute() && Files.isExecutable(node));
        long millis = timeout.toMillis();
        PluginSchema.require(millis >= 100 && millis <= 60_000);
        Path root = directory.toRealPath();
        byte[] manifestBytes = ScriptPlugin.readFile(root.resolve("plugin.json"));
        JsonNode manifest = ScriptPlugin.parse(manifestBytes);
        PluginSchema.fields(
                manifest, Set.of("schemaVersion", "id", "version", "entryPoint", "tools"));
        PluginSchema.require(
                manifest.path("schemaVersion").isIntegralNumber()
                        && manifest.path("schemaVersion").canConvertToInt()
                        && manifest.path("schemaVersion").asInt() == 1);
        String id = ScriptPlugin.text(manifest, "id");
        PluginSchema.require(id.matches("[a-z][a-z0-9_]{0,19}"));
        String version = ScriptPlugin.text(manifest, "version");
        PluginSchema.require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        String entry = ScriptPlugin.text(manifest, "entryPoint");
        PluginSchema.require(entry.matches("[a-zA-Z0-9_-]+\\.mjs"));
        byte[] script = ScriptPlugin.readFile(root.resolve(entry));
        PluginSchema.require(
                manifest.path("tools").isArray()
                        && manifest.path("tools").size() >= 1
                        && manifest.path("tools").size() <= 32);
        List<ScriptTool> descriptors = new ArrayList<>();
        var toolIds = new java.util.HashSet<String>();
        for (JsonNode tool : manifest.path("tools")) {
            PluginSchema.fields(
                    tool, Set.of("id", "description", "handler", "inputSchema", "outputSchema"));
            var descriptor =
                    new ScriptTool(
                            ScriptPlugin.text(tool, "id"),
                            ScriptPlugin.text(tool, "description"),
                            ScriptPlugin.text(tool, "handler"),
                            tool.path("inputSchema"),
                            tool.path("outputSchema"));
            PluginSchema.require(toolIds.add(descriptor.id()));
            descriptors.add(descriptor);
        }
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
        try {
            Path executable = snapshot.resolve(entry);
            Files.write(executable, script);
            return new ScriptPlugin(
                    id,
                    version,
                    digest,
                    descriptors,
                    snapshot,
                    node,
                    executable,
                    millis,
                    host == null ? new ScriptHost(node, millis) : host,
                    host == null);
        } catch (Exception e) {
            ScriptPlugin.removeSnapshot(snapshot);
            throw new IOException(
                    "Plugin loading failed",
                    e instanceof IllegalArgumentException
                            ? null
                            : new IOException(e.getClass().getSimpleName()));
        }
    }
}
