package top.focess.veto.integration.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.RemoteTool;
import top.focess.veto.api.agent.tool.Tool;
import top.focess.veto.api.event.ServiceDirectoryChangedEvent;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.api.plugin.contribution.ProtocolPointDefinition;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceScope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.event.PluginExecutor;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/**
 * Installed-package lifecycle and live catalog. Broken packages fail activation without replacing
 * the last published catalog. Installed Java and script packages are scanned from the plugin
 * directory.
 */
@Component
public final class PluginManager implements AutoCloseable {
    private final @NonNull Map<String, Map<Class<?>, Object>> grantedServices =
            new ConcurrentHashMap<>();

    /** Returns the host service of the given type granted to the plugin, or null when absent. */
    public <T> @Nullable T hostService(@NonNull String plugin, @NonNull Class<T> type) {
        Object value = grantedServices.getOrDefault(plugin, Map.of()).get(type);
        return value == null ? null : type.cast(value);
    }

    private final @NonNull ServiceAccess serviceAccess = new ServiceAccess();

    @SuppressWarnings(
            "methodref.receiver.bound") // Registry callbacks run after manager construction.
    private final @NonNull PluginServiceRegistry serviceRegistry =
            new PluginServiceRegistry(serviceAccess, this::resolveServiceScope);

    private @NonNull ServiceCallContext resolveServiceScope(
            @NonNull String callerId,
            @NonNull String providerId,
            @NonNull ServiceScope required,
            PluginStorage.@NonNull Scope scope)
            throws ServiceException {
        Object bound = grantedServices.getOrDefault(callerId, Map.of()).get(PluginStorage.class);
        Object target = grantedServices.getOrDefault(providerId, Map.of()).get(PluginStorage.class);
        Object factory = baseServices.get(PluginStorageFactory.class);
        if (!(bound instanceof PluginStorage storage)
                || !(target instanceof PluginStorage providerStorage)
                || !(factory instanceof PluginStorageFactory storageFactory))
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        try {
            if (required == ServiceScope.USER && scope instanceof PluginStorage.UserScope user) {
                var issued = storageFactory.transferUser(storage, user, providerStorage);
                return new ServiceCallContext(callerId, required, issued.userId(), null, issued);
            }
            if (required == ServiceScope.SESSION
                    && scope instanceof PluginStorage.SessionScope session) {
                var available = serviceAccess.sessions;
                if (available == null
                        || !available.getObject().includes(session.sessionId(), callerId)
                        || !available.getObject().includes(session.sessionId(), providerId))
                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                var issued = storageFactory.transferSession(storage, session, providerStorage);
                return new ServiceCallContext(
                        callerId, required, issued.userId(), issued.sessionId(), issued);
            }
        } catch (RuntimeException failure) {
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        }
        throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
    }

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
        sessionRepository = sessions;
        sessionInvalidations = invalidations;
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
        private ObjectProvider<SessionPlugins> sessions;

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

    @SuppressWarnings(
            "dereference.of.nullable") // The returned callback runs only after catalog and plugin
    // fields are initialized.
    private @NonNull PluginContributionsDirectory contributionsFor(
            @UnknownInitialization PluginManager this, @NonNull PluginLifecycle caller) {
        return (pointId, major) -> {
            ContributionCatalog snapshot = catalog;
            var definition =
                    snapshot.entries(StandardContributionPoints.CONTRIBUTIONS).stream()
                            .filter(
                                    entry ->
                                            entry.implementation().id().equals(pointId)
                                                    && entry.implementation().major() == major)
                            .findFirst();
            if (definition.isEmpty() || caller.state() != PluginState.ACTIVE) return List.of();
            var pointOwner = definition.orElseThrow();
            if (!serviceAccess.test(caller.identity().id(), pointOwner.source().namespace()))
                return List.of();
            if (plugins.stream()
                    .noneMatch(
                            plugin ->
                                    plugin.identity().id().equals(pointOwner.source().namespace())
                                            && plugin.state() == PluginState.ACTIVE))
                return List.of();
            var result = new ArrayList<PluginContributionsDirectory.Entry>();
            for (var entry : snapshot.entries(pointOwner.implementation().point())) {
                String providerId = entry.source().namespace();
                if (serviceAccess.test(caller.identity().id(), providerId)
                        && plugins.stream()
                                .anyMatch(
                                        plugin ->
                                                plugin.identity().id().equals(providerId)
                                                        && plugin.state() == PluginState.ACTIVE))
                    result.add(
                            new PluginContributionsDirectory.Entry(
                                    entry.id(), providerId, entry.implementation()));
            }
            return List.copyOf(result);
        };
    }

    private final @NonNull ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("veto-plugin-manager").factory());
    private volatile @NonNull List<PluginLifecycle> plugins;
    private volatile @NonNull List<DeclinedPlugin> declined;
    private volatile @NonNull List<InstalledPluginLoader.DisabledPackage> disabled;
    private volatile @NonNull Map<String, String> aliases;
    private volatile @NonNull List<Registration> registrations;
    private volatile @NonNull ContributionCatalog catalog = emptyCatalog();

    private static @NonNull ContributionCatalog emptyCatalog() {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
        return builder.freeze();
    }

    private volatile @NonNull EventListenerRegistry events;
    private final @NonNull Map<@NonNull String, @NonNull String> toolNames;
    private final @NonNull Map<Class<?>, Object> baseServices;
    private final @NonNull PluginConfigurations configurations;
    private final @NonNull String pluginDirectory;
    private final @NonNull String nodeCommand;
    private final boolean trustedCode;
    private final long timeoutMillis;
    private ObjectProvider<ToolEngineImpl> toolEngine;
    private ObjectProvider<PluginLlmProviders> llmProviders;
    private final @NonNull Set<String> installedIds = new HashSet<>();
    private final @NonNull Set<String> dataLifecycleOwners = ConcurrentHashMap.newKeySet();
    private final @NonNull Map<String, Integer> pendingDataCleanups = new HashMap<>();
    private ObjectProvider<SessionRepository> sessionRepository;
    private ObjectProvider<SessionInvalidations> sessionInvalidations;

    /** Attaches catalog consumers after their Spring initialization completes. */
    @Autowired
    public void bindCatalogConsumers(
            @NonNull ObjectProvider<ToolEngineImpl> tools,
            @NonNull ObjectProvider<PluginLlmProviders> providers) {
        toolEngine = tools;
        llmProviders = providers;
    }

    /** A started plugin paired with the contributions it declared at initialization. */
    public record Registration(
            @NonNull PluginLifecycle plugin, @NonNull PluginContributions contributions) {}

    /** Host-owned metadata for an installed plugin that deliberately declined initialization. */
    public record DeclinedPlugin(
            @NonNull String id,
            @NonNull String name,
            @NonNull String version,
            PluginDeclinedException.@NonNull Reason reason) {}

    /**
     * Discovers installed Java and script plugins, binds per-plugin host services, validates all
     * contributions, and starts every plugin; any failure closes the staged plugins.
     *
     * @throws IOException when discovery, validation, or activation fails
     */
    // WHY: staged PluginLifecycle handles are owned by this manager and closed in close(), and the
    // historical-ID null guards stay because third-party plugins can break the @NonNull contract.
    @SuppressWarnings({"resource", "ConstantValue"})
    @Autowired
    public PluginManager(
            @Value("${veto.plugins.directory:plugins}") @NonNull String pluginDirectory,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis,
            @NonNull ObjectProvider<PluginHostServices> hostServices,
            @NonNull PluginConfigurations configurations)
            throws IOException {
        this.configurations = configurations;
        this.pluginDirectory = pluginDirectory;
        this.nodeCommand = nodeCommand;
        this.trustedCode = trustedCode;
        this.timeoutMillis = timeoutMillis;
        toolNames = Map.copyOf(configurations.getToolNames());
        var services = new HashMap<Class<?>, Object>();
        hostServices.orderedStream().forEach(granted -> services.putAll(granted.services()));
        PromptRenderer renderer =
                (source, data) -> PromptCompiler.compileDocument(source, data).text();
        services.put(PromptRenderer.class, renderer);
        baseServices = Map.copyOf(services);
        List<PluginLifecycle> staged = new ArrayList<>();
        List<InstalledPluginLoader.DisabledPackage> disabledPackages = new ArrayList<>();
        try {
            if (!pluginDirectory.isBlank()) {
                var installed =
                        new InstalledPluginLoader(
                                        Path.of(nodeCommand),
                                        Duration.ofMillis(timeoutMillis),
                                        trustedCode,
                                        configurations.getScriptMode())
                                .discover(Path.of(pluginDirectory), configurations.getDisabled());
                disabledPackages.addAll(installed.disabled());
                for (var plugin : installed.plugins()) {
                    installedIds.add(plugin.identity().id());
                    staged.add(new PluginLifecycle(plugin, lifecycle));
                }
                for (var plugin : installed.disabled()) installedIds.add(plugin.id());
            }
            var allIds = new HashSet<String>();
            for (var plugin : staged) {
                if (!allIds.add(plugin.identity().id()))
                    throw new IllegalArgumentException("Duplicate plugin identity");
            }
            for (var plugin : disabledPackages)
                if (!allIds.add(plugin.id()))
                    throw new IllegalArgumentException("Duplicate plugin identity");
            List<Registration> registered = new ArrayList<>();
            List<DeclinedPlugin> declinedPlugins = new ArrayList<>();
            Iterator<PluginLifecycle> pending = staged.iterator();
            while (pending.hasNext()) {
                var plugin = pending.next();
                try {
                    registered.add(initializePlugin(plugin, services, configurations, toolNames));
                } catch (PluginDeclinedException declinedReason) {
                    grantedServices.remove(plugin.identity().id());
                    declinedPlugins.add(
                            new DeclinedPlugin(
                                    plugin.identity().id(),
                                    plugin.displayName(),
                                    plugin.identity().version(),
                                    declinedReason.reason()));
                    pending.remove();
                }
            }
            var aliasLookup = new HashMap<String, String>();
            for (var plugin : staged) {
                var historicalIds = plugin.historicalIds();
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
            var validatedCatalog = buildCatalog(registered);
            serviceRegistry.bind(validatedCatalog, staged);
            catalog = validatedCatalog;
            plugins = List.copyOf(staged);
            for (var plugin : staged) plugin.start();
            declined = List.copyOf(declinedPlugins);
            disabled = List.copyOf(disabledPackages);
            registrations = List.copyOf(registered);
            for (var entry : validatedCatalog.entries(StandardContributionPoints.DATA_LIFECYCLE))
                dataLifecycleOwners.add(entry.source().namespace());
            List<PluginLifecycle> admitted = plugins;
            events =
                    EventListenerRegistry.build(
                            validatedCatalog,
                            (namespace, body) -> admit(admitted, namespace, body));
            events.broadcast(
                    new ServiceDirectoryChangedEvent(),
                    admitted.stream()
                            .map(plugin -> plugin.identity().id())
                            .collect(Collectors.toSet()));
        } catch (Exception | ServiceConfigurationError e) {
            for (var plugin : staged.reversed()) {
                serviceRegistry.revoke(plugin.identity().id());
                plugin.close();
            }
            lifecycle.shutdown();
            throw new IOException("Plugin activation failed", e);
        }
    }

    private @NonNull Registration initializePlugin(
            @UnknownInitialization PluginManager this,
            @NonNull PluginLifecycle plugin,
            @NonNull Map<Class<?>, Object> availableServices,
            @NonNull PluginConfigurations settings,
            @NonNull Map<@NonNull String, @NonNull String> names)
            throws PluginFailure {
        var pluginServices = new HashMap<Class<?>, Object>(availableServices);
        Object optionalGrants = pluginServices.remove(PluginServiceGrants.class);
        if (optionalGrants instanceof PluginServiceGrants grants)
            pluginServices.putAll(grants.forPlugin(plugin));
        Object storageFactory = pluginServices.remove(PluginStorageFactory.class);
        if (storageFactory instanceof PluginStorageFactory factory)
            pluginServices.put(PluginStorage.class, factory.bind(plugin));
        Object agentFactory = pluginServices.remove(PluginAgentHostFactory.class);
        Object boundStorage = pluginServices.get(PluginStorage.class);
        if (boundStorage instanceof PluginStorage storage)
            pluginServices.put(
                    CatalogueAccess.class,
                    new PluginCatalogueAccess(
                            plugin,
                            storage,
                            settings.getCatalogueRoots()
                                    .getOrDefault(plugin.identity().id(), Map.of())));
        if (agentFactory instanceof PluginAgentHostFactory factory
                && boundStorage instanceof PluginStorage storage)
            pluginServices.put(AgentHost.class, factory.bind(plugin, storage));
        Object processFactory = pluginServices.remove(PluginProcessHostFactory.class);
        if (processFactory instanceof PluginProcessHostFactory factory
                && boundStorage instanceof PluginStorage storage)
            pluginServices.put(ProcessHost.class, factory.bind(plugin, storage));
        Object runtimeHost = pluginServices.get(PluginHost.class);
        if (runtimeHost instanceof PluginHost host
                && boundStorage instanceof PluginStorage storage
                && storageFactory instanceof PluginStorageFactory factory)
            pluginServices.put(
                    PluginHost.class,
                    new BoundPluginHost(
                            host,
                            plugin,
                            storage,
                            factory,
                            local ->
                                    resolveToolName(
                                            plugin.identity().id(),
                                            plugin.identity().id() + ":" + local,
                                            names,
                                            plugin,
                                            installedIds.contains(plugin.identity().id()))));
        Object localModelFactory = pluginServices.remove(PluginLocalModelFactory.class);
        if (localModelFactory instanceof PluginLocalModelFactory factory)
            pluginServices.put(LocalModelCompletion.class, factory.bind(plugin));
        Object embeddingFactory = pluginServices.remove(PluginEmbeddingFactory.class);
        if (embeddingFactory instanceof PluginEmbeddingFactory factory)
            factory.bind(plugin).ifPresent(model -> pluginServices.put(TextEmbedding.class, model));
        grantedServices.put(plugin.identity().id(), Map.copyOf(pluginServices));
        pluginServices.put(PluginServices.class, serviceRegistry.forPlugin(plugin));
        pluginServices.put(PluginContributionsDirectory.class, contributionsFor(plugin));
        return new Registration(
                plugin,
                plugin.initialize(
                        new PluginContext(
                                plugin.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
                                pluginServices),
                        settings.forPlugin(plugin.identity().id())));
    }

    private static @NonNull ContributionCatalog buildCatalog(
            @NonNull List<Registration> registered) {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) {
            if (point == StandardContributionPoints.TOOLS)
                builder.define(
                        StandardContributionPoints.TOOLS,
                        tool -> {
                            if (tool instanceof RemoteTool portable) {
                                PluginSchema.check(PluginJson.toNode(portable.inputSchema()));
                                PluginSchema.check(PluginJson.toNode(portable.outputSchema()));
                            } else if (tool instanceof CapabilityTool<?> local
                                    && (local instanceof AgentTool<?>
                                            || local instanceof NativeTool<?>)) {
                                ToolSchemaCompiler.compileFromRecord(local.getArgsClass());
                            } else throw new IllegalArgumentException("Unsupported plugin tool");
                        });
            else if (point == StandardContributionPoints.FRONTEND)
                builder.define(
                        StandardContributionPoints.FRONTEND,
                        frontend -> {
                            String module = frontend.module();
                            if (module.isBlank() || module.length() > 1048576)
                                throw new IllegalArgumentException("Invalid frontend module size");
                        });
            else builder.define(point, ignored -> {});
        }
        var points = new HashSet<ContributionPoint<?>>(StandardContributionPoints.ALL);
        for (var registration : registered) {
            var owner = registration.plugin().identity().id();
            for (var contribution : registration.contributions().entries()) {
                if (contribution.point().equals(StandardContributionPoints.CONTRIBUTIONS)) {
                    ProtocolPointDefinition definition =
                            (ProtocolPointDefinition) contribution.implementation();
                    if (definition == null)
                        throw new IllegalArgumentException("Contribution point is missing");
                    if (!definition.id().value().startsWith(owner + ":"))
                        throw new IllegalArgumentException("Contribution point owner mismatch");
                    var schema = PluginJson.toNode(definition.entrySchema());
                    PluginSchema.check(schema);
                    var point = definition.point();
                    if (!points.add(point))
                        throw new IllegalArgumentException("Duplicate contribution point");
                    builder.define(
                            point,
                            value -> PluginSchema.validate(schema, PluginJson.toNode(value)));
                }
            }
        }
        for (var registration : registered)
            for (var contribution : registration.contributions().entries())
                if (!points.contains(contribution.point())
                        && !(contribution.implementation() instanceof JsonValue.ObjectValue))
                    throw new IllegalArgumentException("Unknown contribution point");
        for (var registration : registered) {
            var identity = registration.plugin().identity();
            builder.stage(
                    new ContributionSource(
                            identity.id(), identity.version(), ContributionSource.Origin.PLUGIN),
                    registration.contributions().entries().stream()
                            .filter(entry -> points.contains(entry.point()))
                            .toList());
        }
        var catalog = builder.freeze();
        StandardContributionPoints.validateToolCategories(catalog);
        return catalog;
    }

    /** Whether a known data-cleanup contributor is currently unavailable. */
    public boolean hasInactiveDataLifecycle() {
        return dataLifecycleOwners.stream()
                .anyMatch(
                        id ->
                                plugins.stream()
                                        .noneMatch(plugin -> plugin.identity().id().equals(id)));
    }

    /** Retains a cleanup contributor until its transaction completion callback has run. */
    public synchronized @NonNull PluginLifecycle beginDataCleanup(@NonNull String id) {
        var runtime = plugin(id);
        if (runtime.state() != PluginState.ACTIVE)
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        pendingDataCleanups.merge(id, 1, Integer::sum);
        return runtime;
    }

    /** Releases one transaction's claim on a data-cleanup contributor. */
    public synchronized void endDataCleanup(@NonNull String id) {
        int remaining = pendingDataCleanups.getOrDefault(id, 0);
        if (remaining <= 0) throw new IllegalStateException("No plugin cleanup is pending");
        if (remaining == 1) pendingDataCleanups.remove(id);
        else pendingDataCleanups.put(id, remaining - 1);
        notifyAll();
    }

    /** Withdraws one installed package without changing persisted records or session bindings. */
    public synchronized void disable(@NonNull String id) {
        String canonical = canonicalId(id);
        if (!installedIds.contains(canonical))
            throw new IllegalArgumentException("Plugin is not an installed package");
        var target = plugin(canonical);
        List<PluginLifecycle> nextPlugins =
                plugins.stream().filter(plugin -> plugin != target).toList();
        List<Registration> nextRegistrations =
                registrations.stream()
                        .filter(registration -> registration.plugin() != target)
                        .toList();
        var metadata =
                new InstalledPluginLoader.DisabledPackage(
                        canonical, target.displayName(), target.identity().version());
        List<InstalledPluginLoader.DisabledPackage> nextDisabled = new ArrayList<>(disabled);
        nextDisabled.add(metadata);
        publish(nextPlugins, nextRegistrations, List.copyOf(nextDisabled), declined);
        serviceRegistry.revoke(canonical);
        invalidateSessions(canonical);
        boolean interrupted = false;
        while (pendingDataCleanups.getOrDefault(canonical, 0) > 0) {
            try {
                wait();
            } catch (InterruptedException signal) {
                interrupted = true;
            }
        }
        try {
            target.close();
        } finally {
            grantedServices.remove(canonical);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Loads and activates a previously disabled installed package in this backend process. */
    public synchronized void enable(@NonNull String id) throws IOException {
        if (pluginDirectory.isBlank()
                || !installedIds.contains(id)
                || (!isDisabled(id) && !isDeclined(id)))
            throw new IllegalArgumentException("Plugin is not an inactive installed package");
        InstalledPlugin implementation =
                new InstalledPluginLoader(
                                Path.of(nodeCommand),
                                Duration.ofMillis(timeoutMillis),
                                trustedCode,
                                configurations.getScriptMode())
                        .loadById(Path.of(pluginDirectory), id);
        PluginLifecycle runtime = new PluginLifecycle(implementation, lifecycle);
        try {
            Registration registration =
                    initializePlugin(runtime, baseServices, configurations, toolNames);
            Map<String, String> nextAliases = new HashMap<>(aliases);
            for (String alias : runtime.historicalIds()) {
                String validated = new PluginIdentity(alias, "0.0.0").id();
                if (installedIds.contains(validated)
                        || (nextAliases.containsKey(validated)
                                && !id.equals(nextAliases.get(validated))))
                    throw new IllegalArgumentException("Duplicate plugin identity or alias");
                nextAliases.put(validated, id);
            }
            List<PluginLifecycle> nextPlugins = new ArrayList<>(plugins);
            nextPlugins.add(runtime);
            List<Registration> nextRegistrations = new ArrayList<>(registrations);
            nextRegistrations.add(registration);
            List<InstalledPluginLoader.DisabledPackage> nextDisabled =
                    disabled.stream().filter(packageInfo -> !packageInfo.id().equals(id)).toList();
            List<DeclinedPlugin> nextDeclined =
                    declined.stream().filter(plugin -> !plugin.id().equals(id)).toList();
            // Validate the whole catalog before activating or publishing the new runtime.
            buildCatalog(nextRegistrations);
            runtime.start();
            publish(
                    List.copyOf(nextPlugins),
                    List.copyOf(nextRegistrations),
                    nextDisabled,
                    nextDeclined);
            aliases = Map.copyOf(nextAliases);
            for (var entry : registration.contributions().entries())
                if (entry.point() == StandardContributionPoints.DATA_LIFECYCLE)
                    dataLifecycleOwners.add(id);
            invalidateSessions(id);
        } catch (PluginDeclinedException declinedReason) {
            grantedServices.remove(id);
            List<InstalledPluginLoader.DisabledPackage> nextDisabled =
                    disabled.stream().filter(packageInfo -> !packageInfo.id().equals(id)).toList();
            List<DeclinedPlugin> nextDeclined =
                    new ArrayList<>(
                            declined.stream().filter(plugin -> !plugin.id().equals(id)).toList());
            nextDeclined.add(
                    new DeclinedPlugin(
                            id,
                            implementation.displayName(),
                            implementation.identity().version(),
                            declinedReason.reason()));
            disabled = nextDisabled;
            declined = List.copyOf(nextDeclined);
        } catch (Exception failure) {
            grantedServices.remove(id);
            runtime.close();
            throw new IOException("Plugin enable failed", failure);
        }
    }

    private void publish(
            @NonNull List<PluginLifecycle> nextPlugins,
            @NonNull List<Registration> nextRegistrations,
            @NonNull List<InstalledPluginLoader.DisabledPackage> nextDisabled,
            @NonNull List<DeclinedPlugin> nextDeclined) {
        ContributionCatalog nextCatalog = buildCatalog(nextRegistrations);
        EventListenerRegistry nextEvents =
                EventListenerRegistry.build(
                        nextCatalog, (namespace, body) -> admit(nextPlugins, namespace, body));
        var previousPlugins = plugins;
        var previousRegistrations = registrations;
        var previousDisabled = disabled;
        var previousDeclined = declined;
        var previousCatalog = catalog;
        var previousEvents = events;
        plugins = nextPlugins;
        registrations = nextRegistrations;
        disabled = nextDisabled;
        declined = nextDeclined;
        catalog = nextCatalog;
        events = nextEvents;
        try {
            var tools = toolEngine;
            if (tools != null) tools.getObject().reloadPlugins(this);
            var providers = llmProviders;
            if (providers != null) providers.getObject().reload(this);
            serviceRegistry.bind(nextCatalog, nextPlugins);
            nextEvents.broadcast(
                    new ServiceDirectoryChangedEvent(),
                    nextPlugins.stream()
                            .map(plugin -> plugin.identity().id())
                            .collect(Collectors.toSet()));
        } catch (RuntimeException failure) {
            plugins = previousPlugins;
            registrations = previousRegistrations;
            disabled = previousDisabled;
            declined = previousDeclined;
            catalog = previousCatalog;
            events = previousEvents;
            var tools = toolEngine;
            if (tools != null) tools.getObject().reloadPlugins(this);
            var providers = llmProviders;
            if (providers != null) providers.getObject().reload(this);
            serviceRegistry.bind(previousCatalog, previousPlugins);
            throw failure;
        }
    }

    private void invalidateSessions(@NonNull String id) {
        var repository = sessionRepository;
        var invalidations = sessionInvalidations;
        if (repository == null || invalidations == null) return;
        for (var session : repository.getObject().findAll()) {
            var bindings = session.getPluginBindings();
            if (bindings != null
                    && bindings.stream().anyMatch(binding -> canonicalId(binding.id()).equals(id)))
                invalidations
                        .getObject()
                        .changed(UUID.fromString(session.getId()), "plugin-frontend");
        }
    }

    public @NonNull List<PluginLifecycle> plugins() {
        return plugins;
    }

    /** Installed packages that deliberately declined initialization. */
    public @NonNull List<DeclinedPlugin> declined() {
        return declined;
    }

    /** Installed packages whose entry classes were not loaded by operator choice. */
    public @NonNull List<InstalledPluginLoader.DisabledPackage> disabled() {
        return disabled;
    }

    /** Whether an inactive installed package claims this stable identity. */
    public boolean isDeclined(@NonNull String id) {
        return declined.stream().anyMatch(plugin -> plugin.id().equals(canonicalId(id)));
    }

    /** Whether a package is installed but disabled before class loading. */
    public boolean isDisabled(@NonNull String id) {
        return disabled.stream().anyMatch(plugin -> plugin.id().equals(canonicalId(id)));
    }

    public @NonNull ContributionCatalog catalog() {
        return catalog;
    }

    /** Returns the compiled event dispatch table built from the contributed listeners. */
    public @NonNull EventListenerRegistry events() {
        return events;
    }

    /**
     * Runs an event handler body under the named plugin's admission, translating a checked handler
     * failure into a sanitized {@link PluginFailure}.
     */
    private static void admit(
            @NonNull List<PluginLifecycle> admitted,
            @NonNull String namespace,
            PluginExecutor.@NonNull Body body)
            throws PluginFailure {
        PluginLifecycle target = null;
        for (PluginLifecycle candidate : admitted)
            if (candidate.identity().id().equals(namespace)) {
                target = candidate;
                break;
            }
        if (target == null) throw new PluginFailure(PluginFailure.Code.NOT_READY);
        target.execute(
                () -> {
                    try {
                        body.run();
                    } catch (PluginFailure | RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
                    }
                    return true;
                });
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
    public @NonNull PluginLifecycle plugin(@NonNull String id) {
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
     * Operator alias, plugin preference, or {@code plugin_<namespace>__<local>} fallback. Shared by
     * schema and Java tools; names do not change provenance or authority.
     */
    public @NonNull String toolName(@NonNull String namespace, @NonNull String qualifiedId) {
        return resolveToolName(
                namespace,
                qualifiedId,
                toolNames,
                plugin(namespace),
                installedIds.contains(namespace));
    }

    private static @NonNull String resolveToolName(
            @NonNull String namespace,
            @NonNull String qualifiedId,
            @NonNull Map<String, String> names,
            @NonNull PluginLifecycle implementation,
            boolean installed) {
        String alias = names.get(qualifiedId);
        if (alias != null) return alias;
        String local = qualifiedId.substring(qualifiedId.indexOf(':') + 1);
        String preferred = installed ? implementation.preferredToolName(local) : null;
        if (preferred != null) {
            if (preferred.isBlank()) throw new IllegalArgumentException("Blank plugin tool name");
            return preferred;
        }
        return "plugin_" + namespace.replace('.', '_').replace('-', '_') + "__" + local;
    }

    /**
     * Session-less observation masking: threads the text through every ACTIVE plugin's {@code
     * veto:observation-middleware} contribution in catalog order, regardless of session bindings.
     * With no contributor the text is returned unchanged.
     */
    @SuppressWarnings(
            "resource") // WHY: PluginLifecycle handle is owned by this manager, closed in close()
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
            for (var plugin : plugins.reversed()) {
                serviceRegistry.revoke(plugin.identity().id());
                plugin.close();
            }
        } finally {
            lifecycle.shutdown();
        }
    }
}
