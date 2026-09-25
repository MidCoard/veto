package top.focess.veto.integration.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/**
 * Startup-only operator configuration. All packages must start or the application fails startup.
 * Built-in plugins are discovered through {@link ServiceLoader}; installed script packages come
 * from operator configuration.
 */
@Component
public final class PluginManager implements AutoCloseable {
    private final @NonNull Map<String, Map<Class<?>, Object>> grantedServices = new HashMap<>();

    /** Returns the host service of the given type granted to the plugin, or null when absent. */
    public <T> @Nullable T hostService(@NonNull String plugin, @NonNull Class<T> type) {
        Object value = grantedServices.getOrDefault(plugin, Map.of()).get(type);
        return value == null ? null : type.cast(value);
    }

    private final @NonNull ServiceAccess serviceAccess = new ServiceAccess();
    private final @NonNull PluginServiceRegistry serviceRegistry =
            new PluginServiceRegistry(serviceAccess);

    /** Binds the session-selection provider used to gate cross-plugin service access. */
    @Autowired
    public void bindServiceSessions(@NonNull ObjectProvider<SessionPlugins> sessions) {
        serviceAccess.sessions = sessions;
    }

    /**
     * Owns, per plugin, a release action that invalidates the plugin frontend of every session
     * selecting it when the plugin shuts down.
     */
    @Autowired
    public void bindLifecycleInvalidations(
            @NonNull ObjectProvider<SessionRepository> sessions,
            @NonNull ObjectProvider<SessionInvalidations> invalidations) {
        for (var plugin : plugins)
            plugin.ownResource(
                    () -> {
                        for (var session : sessions.getObject().findAll()) {
                            var selected = session.getPluginBindings();
                            if (selected != null
                                    && selected.stream()
                                            .anyMatch(
                                                    binding ->
                                                            canonicalId(binding.id())
                                                                    .equals(
                                                                            plugin.identity()
                                                                                    .id())))
                                invalidations
                                        .getObject()
                                        .changed(
                                                UUID.fromString(session.getId()),
                                                "plugin-frontend");
                        }
                    });
    }

    private static final class ServiceAccess
            implements BiPredicate<@NonNull String, @NonNull String> {
        @SuppressWarnings(
                "NullableProblems") // WHY: bound lazily by Spring, so NullnessChecker needs this
        // @Nullable
        private @Nullable ObjectProvider<SessionPlugins> sessions;

        public boolean test(@NonNull String caller, @NonNull String provider) {
            var call = ToolCallContextHolder.get();
            if (call == null) return true;
            var available = sessions;
            var id = call.sessionId();
            if (available == null || id == null) return false;
            var selected = available.getObject();
            return (caller.isEmpty() || selected.includes(id.toString(), caller))
                    && selected.includes(id.toString(), provider);
        }
    }

    /** Host-side view of the plugin service registry. */
    public @NonNull PluginServices services() {
        return serviceRegistry.forHost();
    }

    /** Service-registry view scoped to the plugin with the given identity. */
    public @NonNull PluginServices services(@NonNull String caller) {
        return serviceRegistry.forPlugin(plugin(caller));
    }

    private final @NonNull ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("veto-plugin-manager").factory());
    private final @NonNull List<ManagedPlugin> plugins;
    private final @NonNull Map<String, String> aliases;
    private final @NonNull List<Registration> registrations;
    private final @NonNull ContributionCatalog catalog;
    private final @NonNull Map<@NonNull String, @NonNull String> toolNames;

    /** A started plugin paired with the contributions it declared at initialization. */
    public record Registration(
            @NonNull ManagedPlugin plugin, @NonNull PluginContributions contributions) {}

    /** Convenience constructor using default operator configuration. */
    public PluginManager(
            @NonNull String paths,
            @NonNull String nodeCommand,
            boolean trustedCode,
            long timeoutMillis,
            @NonNull ObjectProvider<PluginHostServices> hostServices)
            throws IOException {
        this(
                paths,
                nodeCommand,
                trustedCode,
                timeoutMillis,
                hostServices,
                new PluginConfigurations());
    }

    /**
     * Discovers built-in and script plugins, binds per-plugin host services, validates all
     * contributions, and starts every plugin; any failure closes the staged plugins.
     *
     * @throws IOException when discovery, validation, or activation fails
     */
    // WHY: staged ManagedPlugin handles are owned by this manager and closed in close(), and the
    // historical-ID null guards stay because third-party plugins can break the @NonNull contract.
    @SuppressWarnings({"resource", "ConstantValue"})
    @Autowired
    public PluginManager(
            @Value("${veto.plugins.paths:}") @NonNull String paths,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis,
            @NonNull ObjectProvider<PluginHostServices> hostServices,
            @NonNull PluginConfigurations configurations)
            throws IOException {
        toolNames = Map.copyOf(configurations.getToolNames());
        var services = new HashMap<Class<?>, Object>();
        hostServices.orderedStream().forEach(granted -> services.putAll(granted.services()));
        PromptRenderer renderer =
                (source, data) -> PromptCompiler.compileDocument(source, data).text();
        services.put(ToolDocs.nonNullClass(PromptRenderer.class), renderer);
        List<ManagedPlugin> staged = new ArrayList<>();
        try {
            if (!paths.isBlank()) configurations.getScriptMode().requireAvailable(trustedCode);
            // Provider failures are fatal: a broken built-in must not silently drop its points.
            for (var discovered : ServiceLoader.load(VetoPlugin.class))
                staged.add(new ManagedPlugin(discovered, lifecycle));
            if (!paths.isBlank()) {
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
                                            Path.of(nodeCommand), Duration.ofMillis(timeoutMillis))
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
            }
            var aliasLookup = new HashMap<String, String>();
            for (var plugin : staged) {
                var historicalIds = plugin.implementation().historicalIds();
                if (historicalIds == null)
                    throw new IllegalArgumentException("Plugin historical IDs must not be null");
                for (String alias : historicalIds) {
                    if (alias == null)
                        throw new IllegalArgumentException("Plugin historical ID must not be null");
                    String validated = new PluginIdentity(alias, "0.0.0").id();
                    if (allIds.contains(validated)
                            || aliasLookup.putIfAbsent(validated, plugin.identity().id()) != null)
                        throw new IllegalArgumentException("Duplicate plugin identity or alias");
                }
            }
            aliases = Map.copyOf(aliasLookup);
            List<Registration> registered = new ArrayList<>();
            for (var plugin : staged) {
                var pluginServices = new HashMap<Class<?>, Object>(services);
                Object optionalGrants = pluginServices.remove(PluginServiceGrants.class);
                if (optionalGrants instanceof PluginServiceGrants grants)
                    pluginServices.putAll(grants.forPlugin(plugin));
                Object storageFactory = pluginServices.remove(PluginStorageFactory.class);
                if (storageFactory instanceof PluginStorageFactory factory)
                    pluginServices.put(
                            ToolDocs.nonNullClass(PluginStorage.class), factory.bind(plugin));
                Object agentFactory = pluginServices.remove(PluginAgentHostFactory.class);
                Object boundStorage = pluginServices.get(PluginStorage.class);
                if (boundStorage instanceof PluginStorage storage)
                    pluginServices.put(
                            ToolDocs.nonNullClass(CatalogueAccess.class),
                            new PluginCatalogueAccess(
                                    plugin,
                                    storage,
                                    configurations
                                            .getCatalogueRoots()
                                            .getOrDefault(plugin.identity().id(), Map.of())));
                if (agentFactory instanceof PluginAgentHostFactory factory
                        && boundStorage instanceof PluginStorage storage)
                    pluginServices.put(
                            ToolDocs.nonNullClass(AgentHost.class), factory.bind(plugin, storage));
                Object processFactory = pluginServices.remove(PluginProcessHostFactory.class);
                if (processFactory instanceof PluginProcessHostFactory factory
                        && boundStorage instanceof PluginStorage storage)
                    pluginServices.put(
                            ToolDocs.nonNullClass(ProcessHost.class),
                            factory.bind(plugin, storage));
                Object runtimeHost = pluginServices.get(PluginHost.class);
                if (runtimeHost instanceof PluginHost host
                        && boundStorage instanceof PluginStorage storage
                        && storageFactory instanceof PluginStorageFactory factory)
                    pluginServices.put(
                            ToolDocs.nonNullClass(PluginHost.class),
                            new BoundPluginHost(
                                    host,
                                    plugin,
                                    storage,
                                    factory,
                                    local ->
                                            resolveToolName(
                                                    plugin.identity().id(),
                                                    plugin.identity().id() + ":" + local,
                                                    toolNames)));
                Object localModelFactory = pluginServices.remove(PluginLocalModelFactory.class);
                if (localModelFactory instanceof PluginLocalModelFactory factory)
                    pluginServices.put(
                            ToolDocs.nonNullClass(LocalModelCompletion.class),
                            factory.bind(plugin));
                Object embeddingFactory = pluginServices.remove(PluginEmbeddingFactory.class);
                if (embeddingFactory instanceof PluginEmbeddingFactory factory)
                    factory.bind(plugin)
                            .ifPresent(
                                    model ->
                                            pluginServices.put(
                                                    ToolDocs.nonNullClass(TextEmbedding.class),
                                                    model));
                grantedServices.put(plugin.identity().id(), Map.copyOf(pluginServices));
                pluginServices.put(
                        ToolDocs.nonNullClass(PluginServices.class),
                        serviceRegistry.forPlugin(plugin));
                registered.add(
                        new Registration(
                                plugin,
                                plugin.initialize(
                                        new PluginContext(
                                                plugin.identity(),
                                                () -> {},
                                                () -> {
                                                    throw new IllegalStateException(
                                                            "Plugin context is not bound to a"
                                                                    + " lifecycle owner");
                                                },
                                                pluginServices),
                                        configurations.forPlugin(plugin.identity().id()))));
            }
            var builder = new ContributionCatalog.Builder();
            // Define every standard point up front so an unpopulated point yields an empty entry
            // list rather than a registration error. The "no contributor -> unchanged" contract
            // (see applyObservationMiddleware) and lifecycle dispatch depend on this: the floor
            // optional plugins may populate them, but the host must not break when a deployment
            // runs without contributors.
            for (var point : StandardContributionPoints.ALL) {
                if (point == StandardContributionPoints.TOOLS)
                    builder.define(
                            StandardContributionPoints.TOOLS,
                            tool -> {
                                PluginSchema.check(PluginJson.toNode(tool.inputSchema()));
                                PluginSchema.check(PluginJson.toNode(tool.outputSchema()));
                            });
                else if (point == StandardContributionPoints.NATIVE_TOOLS)
                    builder.define(
                            StandardContributionPoints.NATIVE_TOOLS,
                            tool -> ToolSchemaCompiler.compileFromRecord(tool.getArgsClass()));
                else builder.define(point, ignored -> {});
            }
            var points = new HashSet<ContributionPoint<?>>(StandardContributionPoints.ALL);
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
            serviceRegistry.bind(validatedCatalog, staged);
            for (var plugin : staged) plugin.start();
            catalog = validatedCatalog;
            plugins = List.copyOf(staged);
            registrations = List.copyOf(registered);
        } catch (Exception | ServiceConfigurationError e) {
            for (var plugin : staged.reversed()) plugin.close();
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

    /** Returns the loaded script-package plugins, excluding built-ins. */
    public @NonNull List<ScriptPlugin> scriptPlugins() {
        List<ScriptPlugin> scripts = new ArrayList<>();
        for (var plugin : plugins)
            if (plugin.implementation() instanceof ScriptPlugin script) scripts.add(script);
        return List.copyOf(scripts);
    }

    /** Returns the managed plugin for the given (alias-resolved) id; throws when unknown. */
    public @NonNull ManagedPlugin plugin(@NonNull String id) {
        return plugins.stream()
                .filter(plugin -> plugin.identity().id().equals(canonicalId(id)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plugin identity"));
    }

    /** Maps a historical plugin id to its canonical identity; unknown ids pass through. */
    public @NonNull String canonicalId(@NonNull String id) {
        return aliases.getOrDefault(id, id);
    }

    /** Resolves the runtime tool name of the given contributed tool entry. */
    public @NonNull String toolName(@NonNull ContributionEntry<Tool> entry) {
        return toolName(entry.source().namespace(), entry.id().value());
    }

    /**
     * Operator-configured alias, or {@code plugin_<namespace>__<local>} by default. Shared by
     * schema and Java tools; names do not change provenance or authority.
     */
    public @NonNull String toolName(@NonNull String namespace, @NonNull String qualifiedId) {
        return resolveToolName(namespace, qualifiedId, toolNames);
    }

    private static @NonNull String resolveToolName(
            @NonNull String namespace,
            @NonNull String qualifiedId,
            @NonNull Map<String, String> names) {
        String alias = names.get(qualifiedId);
        if (alias != null) return alias;
        String local = qualifiedId.substring(qualifiedId.indexOf(':') + 1);
        return "plugin_" + namespace.replace('.', '_').replace('-', '_') + "__" + local;
    }

    /**
     * Session-less observation masking: threads the text through every ACTIVE plugin's {@code
     * veto:observation-middleware} contribution in catalog order, regardless of session bindings.
     * With no contributor the text is returned unchanged.
     */
    @SuppressWarnings(
            "resource") // WHY: ManagedPlugin handle is owned by this manager, closed in close()
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
            lifecycle.shutdown();
        }
    }
}
