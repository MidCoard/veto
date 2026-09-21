package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.plugin.api.PluginContext;
import top.focess.veto.plugin.api.PluginContributions;
import top.focess.veto.plugin.api.PluginState;
import top.focess.veto.plugin.api.VetoPlugin;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.PluginFailure;
import top.focess.veto.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.contract.Tool;
import top.focess.veto.plugin.contribution.ContributionCatalog;
import top.focess.veto.plugin.contribution.ContributionEntry;
import top.focess.veto.plugin.contribution.ContributionPoint;
import top.focess.veto.plugin.contribution.ContributionSource;

/**
 * Startup-only operator configuration. All packages must start or the application fails startup.
 * Built-in plugins are discovered through {@link ServiceLoader}; installed script packages come
 * from operator configuration.
 */
@Component
public final class PluginManager implements AutoCloseable {
    private final @NonNull ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("veto-plugin-manager").factory());
    private final @NonNull ScriptHost scriptHost;
    private final @NonNull List<ManagedPlugin> plugins;
    private final @NonNull List<Registration> registrations;
    private final @NonNull ContributionCatalog catalog;

    public record Registration(
            @NonNull ManagedPlugin plugin, @NonNull PluginContributions contributions) {}

    @org.springframework.beans.factory.annotation.Autowired
    public PluginManager(
            @Value("${veto.plugins.paths:}") @NonNull String paths,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis,
            @NonNull ObjectProvider<PluginHostServices> hostServices)
            throws IOException {
        scriptHost = new ScriptHost(Path.of(nodeCommand), timeoutMillis);
        var granted = hostServices.getIfAvailable();
        var services = granted == null ? Map.<Class<?>, Object>of() : granted.services();
        List<ManagedPlugin> staged = new ArrayList<>();
        try {
            // Provider failures are fatal: a broken built-in must not silently drop its points.
            for (var discovered : ServiceLoader.load(VetoPlugin.class))
                staged.add(new ManagedPlugin(discovered, lifecycle));
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
            List<Registration> registered = new ArrayList<>();
            for (var plugin : staged) {
                if (!allIds.add(plugin.identity().id()))
                    throw new IllegalArgumentException("Duplicate plugin identity");
                registered.add(
                        new Registration(
                                plugin,
                                plugin.initialize(
                                        new PluginContext(plugin.identity(), services),
                                        new JsonValue.ObjectValue(Map.of()))));
            }
            var builder = new ContributionCatalog.Builder();
            var points = new java.util.HashSet<ContributionPoint<?>>();
            points.add(StandardContributionPoints.TOOLS);
            points.add(StandardContributionPoints.CATEGORIES);
            builder.define(
                    StandardContributionPoints.TOOLS,
                    tool -> {
                        PluginSchema.check(PluginJson.toNode(tool.inputSchema()));
                        PluginSchema.check(PluginJson.toNode(tool.outputSchema()));
                    });
            builder.define(StandardContributionPoints.CATEGORIES, ignored -> {});
            for (var registration : registered) {
                for (var contribution : registration.contributions().entries()) {
                    if (points.add(contribution.point()))
                        builder.define(contribution.point(), ignored -> {});
                }
            }
            for (var registration : registered) {
                var identity = registration.plugin().identity();
                builder.stage(
                        new ContributionSource(
                                identity.id(),
                                identity.version(),
                                ContributionSource.Origin.PLUGIN),
                        registration.contributions().entries());
            }
            var validatedCatalog = builder.freeze();
            StandardContributionPoints.validateToolCategories(validatedCatalog);
            for (var plugin : staged) plugin.start();
            catalog = validatedCatalog;
            plugins = List.copyOf(staged);
            registrations = List.copyOf(registered);
        } catch (Exception | ServiceConfigurationError e) {
            for (var plugin : staged.reversed()) plugin.close();
            scriptHost.close();
            lifecycle.shutdown();
            throw new IOException("Plugin activation failed", e);
        }
    }

    public @NonNull List<ManagedPlugin> plugins() {
        return plugins;
    }

    public @NonNull ContributionCatalog catalog() {
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

    public @NonNull String toolName(@NonNull ContributionEntry<Tool> entry) {
        String qualified = entry.id().value();
        String local = qualified.substring(qualified.indexOf(':') + 1);
        return "plugin_"
                + entry.source().namespace().replace('.', '_').replace('-', '_')
                + "__"
                + local;
    }

    /**
     * Session-less observation masking: threads the text through every ACTIVE plugin's {@code
     * veto:observation-middleware} contribution in catalog order, regardless of session bindings.
     * With no contributor the text is returned unchanged; the floor ships on the runtime classpath
     * as the secret-protection plugin.
     */
    public @NonNull String applyObservationMiddleware(@NonNull String text) {
        String result = text;
        for (var entry : catalog.entries(StandardContributionPoints.OBSERVATION)) {
            var plugin = plugin(entry.source().namespace());
            if (plugin.state() != PluginState.ACTIVE) continue;
            String input = result;
            try {
                result =
                        plugin.execute(
                                () ->
                                        entry.implementation()
                                                .transform(
                                                        input,
                                                        () ->
                                                                Thread.currentThread()
                                                                        .isInterrupted()));
            } catch (PluginFailure failure) {
                throw new IllegalStateException(
                        "Session-less observation masking is unavailable", failure);
            }
        }
        return result;
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
