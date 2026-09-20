package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import top.focess.veto.extension.ExtensionCatalog;
import top.focess.veto.extension.ExtensionEntry;
import top.focess.veto.extension.ExtensionPoint;
import top.focess.veto.extension.ExtensionSource;
import top.focess.veto.extension.contract.JsonValue;
import top.focess.veto.extension.contract.StandardExtensionPoints;
import top.focess.veto.extension.contract.ToolContribution;
import top.focess.veto.plugin.api.AbstractVetoPlugin;
import top.focess.veto.plugin.api.PluginContext;
import top.focess.veto.plugin.api.PluginContributions;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Startup-only operator configuration. All packages must start or the application fails startup.
 */
@Component
public final class PluginManager implements AutoCloseable {
    private final @NonNull ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("veto-plugin-manager").factory());
    private final @NonNull ScriptHost scriptHost;
    private final @NonNull List<ManagedPlugin> plugins;
    private final @NonNull List<Registration> registrations;
    private final @NonNull ExtensionCatalog catalog;

    public record Registration(
            @NonNull ManagedPlugin plugin, @NonNull PluginContributions contributions) {}

    @org.springframework.beans.factory.annotation.Autowired
    public PluginManager(
            @Value("${veto.plugins.paths:}") @NonNull String paths,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis,
            @NonNull List<AbstractVetoPlugin> builtins)
            throws IOException {
        scriptHost = new ScriptHost(Path.of(nodeCommand), timeoutMillis);
        List<ManagedPlugin> staged = new ArrayList<>();
        for (var builtin : builtins) staged.add(new ManagedPlugin(builtin, lifecycle));
        List<Registration> registered = new ArrayList<>();
        try {
            if (!paths.isBlank()) {
                if (!trustedCode)
                    throw new IllegalArgumentException(
                            "Configured plugins require veto.plugins.trusted-code=true; local"
                                    + " scripts run as the server user");
                var ids = new HashSet<String>();
                var entries = paths.split(",", -1);
                if (entries.length > 16)
                    throw new IllegalArgumentException("Too many plugin packages");
                for (String entry : entries) {
                    Path directory = Path.of(entry.strip());
                    if (!directory.isAbsolute())
                        throw new IllegalArgumentException("Plugin paths must be absolute");
                    ScriptPlugin plugin =
                            new ScriptPluginLoader(
                                            Path.of(nodeCommand),
                                            Duration.ofMillis(timeoutMillis),
                                            scriptHost)
                                    .load(directory);
                    staged.add(new ManagedPlugin(plugin, lifecycle));
                    if (!ids.add(plugin.id()))
                        throw new IllegalArgumentException("Duplicate plugin identity");
                }
            }
            var allIds = new HashSet<String>();
            for (var plugin : staged) {
                if (!allIds.add(plugin.identity().id()))
                    throw new IllegalArgumentException("Duplicate plugin identity");
                registered.add(
                        new Registration(
                                plugin,
                                plugin.initialize(
                                        new PluginContext(plugin.identity()),
                                        new JsonValue.ObjectValue(java.util.Map.of()))));
            }
            var builder = new ExtensionCatalog.Builder();
            var points = new java.util.HashSet<ExtensionPoint<?>>();
            points.add(StandardExtensionPoints.TOOLS);
            points.add(StandardExtensionPoints.CATEGORIES);
            builder.define(
                    StandardExtensionPoints.TOOLS,
                    tool -> {
                        PluginSchema.check(PluginJson.toNode(tool.inputSchema()));
                        PluginSchema.check(PluginJson.toNode(tool.outputSchema()));
                    });
            builder.define(StandardExtensionPoints.CATEGORIES, ignored -> {});
            for (var registration : registered) {
                for (var contribution : registration.contributions().entries()) {
                    if (points.add(contribution.point()))
                        builder.define(contribution.point(), ignored -> {});
                }
            }
            for (var registration : registered) {
                var identity = registration.plugin().identity();
                builder.stage(
                        new ExtensionSource(
                                identity.id(), identity.version(), ExtensionSource.Origin.PLUGIN),
                        registration.contributions().entries());
            }
            var validatedCatalog = builder.freeze();
            StandardExtensionPoints.validateToolCategories(validatedCatalog);
            for (var plugin : staged) plugin.start();
            catalog = validatedCatalog;
            plugins = List.copyOf(staged);
            registrations = List.copyOf(registered);
        } catch (Exception e) {
            for (var plugin : staged.reversed()) plugin.close();
            scriptHost.close();
            lifecycle.shutdown();
            throw new IOException("Plugin activation failed", e);
        }
    }

    public @NonNull List<ManagedPlugin> plugins() {
        return plugins;
    }

    public @NonNull ExtensionCatalog catalog() {
        return catalog;
    }

    public @NonNull List<Registration> registrations() {
        return registrations;
    }

    public @NonNull List<ScriptPlugin> scriptPlugins() {
        List<ScriptPlugin> scripts = new ArrayList<>();
        for (var plugin : plugins)
            if (plugin.implementation() instanceof ScriptPlugin script) scripts.add(script);
        return List.copyOf(scripts);
    }

    public @NonNull ManagedPlugin plugin(@NonNull String id) {
        return plugins.stream()
                .filter(plugin -> plugin.identity().id().equals(PluginBinding.canonicalId(id)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plugin identity"));
    }

    public @NonNull String toolName(@NonNull ExtensionEntry<ToolContribution> entry) {
        String qualified = entry.id().value();
        String local = qualified.substring(qualified.indexOf(':') + 1);
        return "plugin_"
                + entry.source().namespace().replace('.', '_').replace('-', '_')
                + "__"
                + local;
    }

    @Override
    public void close() {
        try {
            plugins.reversed().forEach(ManagedPlugin::close);
        } finally {
            scriptHost.close();
            lifecycle.shutdown();
        }
    }
}
