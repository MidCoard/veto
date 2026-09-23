package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.api.plugin.contract.ToolContribution;
import top.focess.veto.api.plugin.contribution.Contribution;

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
    private final @NonNull ScriptHost host;
    private final boolean ownsHost;
    private final @NonNull Path executable;

    private @NonNull Runnable failureReporter = () -> {};

    ScriptPlugin(
            @NonNull String id,
            @NonNull String version,
            @NonNull String digest,
            @NonNull List<ScriptTool> descriptors,
            @NonNull Path snapshot,
            @NonNull Path node,
            @NonNull Path executable,
            long timeoutMillis,
            @NonNull ScriptHost host,
            boolean ownsHost) {
        this.host = host;
        this.ownsHost = ownsHost;
        this.id = id;
        this.version = version;
        this.digest = digest;
        this.descriptors = List.copyOf(descriptors);
        this.snapshot = snapshot;
        this.executable = executable;
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        failureReporter = context::reportFailure;
        PluginSchema.require(configuration.values().isEmpty());
        return new PluginContributions(
                tools().stream()
                        .<Contribution<?>>map(
                                tool ->
                                        Contribution.of(
                                                StandardContributionPoints.TOOLS,
                                                tool.id(),
                                                new ToolContribution(
                                                        tool.description(),
                                                        PluginJson.object(tool.inputSchema()),
                                                        PluginJson.object(tool.outputSchema()),
                                                        Tool.Effect.EXTERNAL_UNKNOWN,
                                                        Set.of(),
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
                                                                throw new PluginFailure(
                                                                        PluginFailure.Code
                                                                                .INTERNAL_FAILURE);
                                                            } catch (
                                                                    IllegalArgumentException
                                                                            failure) {
                                                                // Invalid tool arguments must
                                                                // surface as a contract failure,
                                                                // not a raw runtime exception.
                                                                throw new PluginFailure(
                                                                        PluginFailure.Code
                                                                                .INVALID_ARGUMENTS);
                                                            }
                                                        })))
                        .toList());
    }

    @Override
    protected void onStart() throws IOException {
        host.register(digest, failureReporter);
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
        return host.registered(digest);
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
        JsonNode result = host.invoke(digest, executable, tool.handler(), arguments);
        try {
            PluginSchema.validate(tool.outputSchema(), result);
        } catch (IllegalArgumentException e) {
            failureReporter.run();
            throw new IOException("Invalid plugin result");
        }
        return result;
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
        host.unregister(digest);
        if (ownsHost) host.close();
        removeSnapshot(snapshot);
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
