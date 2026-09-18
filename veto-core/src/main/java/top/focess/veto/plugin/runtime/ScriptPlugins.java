package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Startup-only operator configuration. All packages must start or the application fails startup.
 */
@Component
public final class ScriptPlugins implements AutoCloseable {
    private final @NonNull List<ScriptPlugin> plugins;

    public ScriptPlugins(
            @Value("${veto.plugins.paths:}") @NonNull String paths,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis)
            throws IOException {
        List<ScriptPlugin> staged = new ArrayList<>();
        try {
            if (!paths.isBlank()) {
                if (!trustedCode)
                    throw new IllegalArgumentException(
                            "Configured plugins require veto.plugins.trusted-code=true; local scripts run as the server user");
                var ids = new HashSet<String>();
                var entries = paths.split(",", -1);
                if (entries.length > 16)
                    throw new IllegalArgumentException("Too many plugin packages");
                for (String entry : entries) {
                    Path directory = Path.of(entry.strip());
                    if (!directory.isAbsolute())
                        throw new IllegalArgumentException("Plugin paths must be absolute");
                    ScriptPlugin plugin =
                            ScriptPlugin.load(
                                    directory,
                                    Path.of(nodeCommand),
                                    Duration.ofMillis(timeoutMillis));
                    staged.add(plugin);
                    if (!ids.add(plugin.id()))
                        throw new IllegalArgumentException("Duplicate plugin identity");
                }
            }
            plugins = List.copyOf(staged);
            for (var plugin : plugins)
                LoggerFactory.getLogger(ScriptPlugins.class)
                        .info(
                                "Plugin {} version {} active ({} tools, sha256={})",
                                plugin.id(),
                                plugin.version(),
                                plugin.tools().size(),
                                plugin.digest());
        } catch (IOException | RuntimeException e) {
            staged.forEach(ScriptPlugin::close);
            throw e;
        }
    }

    public @NonNull List<ScriptPlugin> plugins() {
        return plugins;
    }

    @Override
    public void close() {
        plugins.forEach(ScriptPlugin::close);
    }
}
