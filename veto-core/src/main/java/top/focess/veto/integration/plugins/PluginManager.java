package top.focess.veto.integration.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.RemoteTool;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.ServiceDirectoryChangedEvent;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.api.plugin.contribution.ProtocolPointDefinition;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/**
 * Installed-package lifecycle and live catalog. Broken packages fail activation without replacing
 * the last published catalog. Installed Java and script packages are scanned from the plugin
 * directory.
 *
 * <p>Threading: startup construction is externally owned. After publication, readers use volatile
 * immutable query registries and concurrent host-service maps. Capture {@link #registry()} when
 * catalog entries and their owning activations must come from one publication; separate captures
 * need not observe the same publication. Active contribution registration serializes validation and
 * republication on the manager monitor. Desired activation writes and data-cleanup claim counts use
 * that same monitor. Point validators and metadata getters may run during republication under it
 * and must not block or wait for another management operation. Catalog-change listeners also run
 * inline during republication and must follow that restriction. Ordinary plugin invocations and
 * event submissions use lifecycle admission without taking the manager monitor. Shutdown is
 * externally coordinated and closes activations in reverse order before shutting down their serial
 * control executor.
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

    private final @NonNull ServiceAccess serviceAccess;

    private final @NonNull PluginServiceRegistry serviceRegistry;

    private @NonNull ServiceCallContext resolvePluginScope(
            @NonNull String callerId,
            @NonNull String providerId,
            @NonNull PluginScope required,
            PluginStorage.@NonNull Grant<?> grant)
            throws ServiceException {
        Object bound = grantedServices.getOrDefault(callerId, Map.of()).get(PluginStorage.class);
        Object target = grantedServices.getOrDefault(providerId, Map.of()).get(PluginStorage.class);
        Object factory = baseServices.get(PluginStorageFactory.class);
        if (!(bound instanceof PluginStorage storage)
                || !(target instanceof PluginStorage providerStorage)
                || !(factory instanceof PluginStorageFactory storageFactory))
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        try {
            if (required == PluginScope.USER && grant.scope() instanceof Scope.UserScope user) {
                var issued =
                        storageFactory.transferUser(
                                storage,
                                new PluginStorage.Grant<>(grant.token(), user),
                                providerStorage);
                return new ServiceCallContext(callerId, required, issued.scope(), issued);
            }
            if ((required == PluginScope.SESSION || required == PluginScope.AGENT)
                    && grant.scope() instanceof Scope.SessionScope identity) {
                var session = new PluginStorage.Grant<>(grant.token(), identity);
                var available = serviceAccess.sessions;
                if (!available.getObject().includes(identity.session(), callerId)
                        || !available.getObject().includes(identity.session(), providerId))
                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                var agent =
                        required == PluginScope.AGENT
                                ? AgentServiceScope.authorize(storageFactory, storage, session)
                                : null;
                var issued = storageFactory.transferSession(storage, session, providerStorage);
                return new ServiceCallContext(
                        callerId,
                        required,
                        agent == null
                                ? issued.scope()
                                : new Scope.AgentScope(
                                        issued.scope().owner(),
                                        issued.scope().session(),
                                        agent.agent()),
                        issued);
            }
        } catch (RuntimeException failure) {
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        }
        throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
    }

    /**
     * Owns, per plugin, a release action that invalidates the plugin frontend of every session
     * selecting it when the plugin shuts down.
     */
    private void registerFrontendInvalidations() {
        for (var plugin : published.plugins())
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
        private final @NonNull ObjectProvider<SessionPlugins> sessions;

        private ServiceAccess(@NonNull ObjectProvider<SessionPlugins> sessions) {
            this.sessions = sessions;
        }

        public boolean test(@NonNull String caller, @NonNull String provider) {
            var call = ToolCallContextHolder.get();
            if (call == null) return true;
            var available = sessions;
            var id = call.sessionId();
            if (id == null) return false;
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
        return serviceRegistry.forPlugin(published.plugin(caller));
    }

    // WHY: the returned callback runs only after registry publication initializes host views.
    @SuppressWarnings("dereference.of.nullable")
    @RequiresNonNull("serviceAccess")
    private @NonNull PluginContributionsDirectory contributionsFor(
            @UnknownInitialization PluginManager this, @NonNull ManagedPlugin caller) {
        return (pointId, major) -> published.contributions(pointId, major, caller, serviceAccess);
    }

    private final @NonNull ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("veto-plugin-manager").factory());
    private volatile @NonNull PluginRegistry published = PluginRegistry.empty();
    private @NonNull Map<String, String> aliases = Map.of();
    private @NonNull List<Registration> stagedRegistrations = List.of();
    private volatile boolean ready;

    /** Captures a coherent immutable query registry; lifecycle admission remains live. */
    public @NonNull PluginRegistry registry() {
        return published;
    }

    /**
     * Resolves explicit requested ids to pinned bindings; an empty list selects no plugins.
     * Duplicates and unknown or inactive plugins are rejected.
     */
    public @NonNull List<PluginBinding> selection(@NonNull List<@NonNull String> requested) {
        var publication = registry();
        var ids = requested.stream().map(publication::canonicalId).toList();
        if (ids.size() != Set.copyOf(ids).size())
            throw new IllegalArgumentException("Duplicate plugin selection");
        return ids.stream()
                .map(
                        id -> {
                            var plugin = publication.plugin(id);
                            if (plugin.state() != PluginState.ACTIVE)
                                throw new IllegalArgumentException("Plugin is unavailable: " + id);
                            return plugin.binding();
                        })
                .toList();
    }

    private final EventListenerRegistry.@NonNull Preparation listenerPreparation =
            new EventListenerRegistry.Preparation();
    private final @NonNull Map<@NonNull String, @NonNull String> toolNames;
    private final @NonNull Map<Class<?>, Object> baseServices;
    private final @NonNull PluginConfigurations configurations;
    private final @NonNull PluginActivationStore activationStore;
    private final @NonNull String pluginDirectory;
    private final @NonNull String nodeCommand;
    private final boolean trustedCode;
    private final long timeoutMillis;
    private final @NonNull ObjectProvider<ToolEngineImpl> toolEngine;
    private final @NonNull ObjectProvider<PluginLlmProviders> llmProviders;
    private final @NonNull ObjectProvider<SessionRepository> sessions;
    private final @NonNull ObjectProvider<SessionInvalidations> invalidations;
    private final @NonNull Set<String> installedIds = new HashSet<>();
    private final @NonNull Set<String> dataLifecycleOwners = ConcurrentHashMap.newKeySet();
    private final @NonNull Map<ContributionPoint<?>, PointDefinition> definedPoints =
            new ConcurrentHashMap<>();
    private final @NonNull Map<String, Integer> pendingDataCleanups = new HashMap<>();
    // Guarded by this monitor; close rejects new deletion claims before draining existing ones.
    private boolean closing;

    /** Manager-owned mutable registration staging, never retained by a query registry. */
    private record Registration(@NonNull ManagedPlugin plugin, @NonNull PointRegistrations points) {
        public @NonNull List<@NonNull Contribution<?>> entries() {
            return points.entries();
        }
    }

    /** Mutable only through the plugin's point handler; publication reads an immutable snapshot. */
    private static final class PointRegistrations {
        private final @NonNull
                Map<
                        @NonNull ContributionPoint<?>,
                        @NonNull Map<@NonNull String, @NonNull Contribution<?>>>
                entries = new LinkedHashMap<>();

        private void add(@NonNull Contribution<?> contribution) {
            String id = contribution.localId();
            if (entries.values().stream().anyMatch(group -> group.containsKey(id)))
                throw new IllegalArgumentException("Duplicate contribution identity");
            entries.computeIfAbsent(contribution.point(), ignored -> new LinkedHashMap<>())
                    .put(id, contribution);
        }

        private void remove(@NonNull Contribution<?> contribution) {
            var group = entries.get(contribution.point());
            if (group != null) group.remove(contribution.localId());
        }

        private @NonNull List<@NonNull Contribution<?>> entries() {
            var result = new ArrayList<Contribution<?>>();
            entries.values().forEach(group -> result.addAll(group.values()));
            return List.copyOf(result);
        }
    }

    /** Host-owned metadata for an installed plugin that deliberately declined initialization. */
    public record DeclinedPlugin(
            @NonNull String id,
            @NonNull String name,
            @NonNull String version,
            PluginDeclinedException.@NonNull Reason reason) {}

    private record PointDefinition(
            @NonNull String owner, @NonNull ProtocolPointDefinition definition) {}

    private @NonNull Map<ContributionPoint<?>, PointDefinition> withdrawDefinitions(
            @UnknownInitialization PluginManager this, @NonNull String owner) {
        Map<ContributionPoint<?>, PointDefinition> removed = new HashMap<>();
        definedPoints.forEach(
                (point, definition) -> {
                    if (definition.owner().equals(owner) && definedPoints.remove(point, definition))
                        removed.put(point, definition);
                });
        return removed;
    }

    /**
     * Discovers installed Java and script plugins, binds per-plugin host services, validates all
     * contributions, and starts every plugin; any failure closes the staged plugins.
     *
     * @throws IOException when discovery, validation, or activation fails
     */
    // WHY: staged ManagedPlugin handles are owned by this manager and closed in close(), and the
    // historical-ID null guards stay because third-party plugins can break the @NonNull contract.
    @SuppressWarnings({"resource", "ConstantValue", "methodref.receiver.bound"})
    public PluginManager(
            @Value("${veto.plugins.directory:plugins}") @NonNull String pluginDirectory,
            @Value("${veto.plugins.node-command:}") @NonNull String nodeCommand,
            @Value("${veto.plugins.trusted-code:false}") boolean trustedCode,
            @Value("${veto.plugins.timeout-ms:5000}") long timeoutMillis,
            @NonNull ObjectProvider<PluginHostServices> hostServices,
            @NonNull PluginConfigurations configurations,
            @NonNull PluginActivationStore activationStore,
            @NonNull ObjectProvider<SessionPlugins> sessionPlugins,
            @NonNull ObjectProvider<SessionRepository> sessions,
            @NonNull ObjectProvider<SessionInvalidations> invalidations,
            @NonNull ObjectProvider<ToolEngineImpl> tools,
            @NonNull ObjectProvider<PluginLlmProviders> providers)
            throws IOException {
        serviceAccess = new ServiceAccess(sessionPlugins);
        serviceRegistry = new PluginServiceRegistry(serviceAccess, this::resolvePluginScope);
        this.sessions = sessions;
        this.invalidations = invalidations;
        toolEngine = tools;
        llmProviders = providers;
        this.configurations = configurations;
        this.pluginDirectory = pluginDirectory;
        this.activationStore = activationStore;
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
        List<ManagedPlugin> staged = new ArrayList<>();
        List<InstalledPluginLoader.DisabledPackage> disabledPackages = new ArrayList<>();
        try {
            if (!pluginDirectory.isBlank()) {
                var installed =
                        new InstalledPluginLoader(
                                        Path.of(nodeCommand),
                                        Duration.ofMillis(timeoutMillis),
                                        trustedCode,
                                        configurations.getScriptMode())
                                .discover(
                                        Path.of(pluginDirectory),
                                        activationStore.disabledIds(configurations.getDisabled()));
                disabledPackages.addAll(installed.disabled());
                for (var plugin : installed.plugins()) {
                    installedIds.add(plugin.identity().id());
                    staged.add(new ManagedPlugin(plugin, lifecycle));
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
            Iterator<ManagedPlugin> pending = staged.iterator();
            while (pending.hasNext()) {
                var plugin = pending.next();
                try {
                    registered.add(constructPlugin(plugin, services, configurations, toolNames));
                } catch (PluginDeclinedException declinedReason) {
                    withdrawDefinitions(plugin.identity().id());
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
            stagedRegistrations = List.copyOf(registered);
            var validatedCatalog = buildCatalog(registered);
            serviceRegistry.bind(validatedCatalog, staged);
            published =
                    new PluginRegistry(
                            staged,
                            disabledPackages,
                            declinedPlugins,
                            validatedCatalog,
                            preparedEvents(validatedCatalog, staged, listenerPreparation),
                            aliases,
                            toolNames,
                            installedIds);
            for (var plugin : staged) plugin.start();
            validatedCatalog = buildCatalog(registered);
            serviceRegistry.bind(validatedCatalog, staged);
            published =
                    new PluginRegistry(
                            staged,
                            disabledPackages,
                            declinedPlugins,
                            validatedCatalog,
                            preparedEvents(validatedCatalog, staged, listenerPreparation),
                            aliases,
                            toolNames,
                            installedIds);
            for (var entry : validatedCatalog.entries(StandardContributionPoints.DATA_LIFECYCLE))
                dataLifecycleOwners.add(entry.source().namespace());
            published.events().submit(new ServiceDirectoryChangedEvent());
            registerFrontendInvalidations();
            ready = true;
        } catch (Exception | ServiceConfigurationError e) {
            definedPoints.clear();
            for (var plugin : staged.reversed()) {
                serviceRegistry.revoke(plugin.identity().id());
                plugin.close();
            }
            lifecycle.shutdown();
            throw new IOException("Plugin activation failed", e);
        }
    }

    @RequiresNonNull({"serviceRegistry", "serviceAccess"})
    private @NonNull Registration constructPlugin(
            @UnknownInitialization PluginManager this,
            @NonNull ManagedPlugin plugin,
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
                                    PluginRegistry.resolveToolName(
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
        var points = new PointRegistrations();
        var handlers = contributionHandlers(plugin, points);
        plugin.construct(
                new PluginContext(
                        plugin.identity(),
                        () -> {},
                        () -> {
                            throw new IllegalStateException(
                                    "Plugin context is not bound to a lifecycle owner");
                        },
                        pluginServices,
                        handlers),
                settings.forPlugin(plugin.identity().id()));
        return new Registration(plugin, points);
    }

    private @NonNull Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
            contributionHandlers(
                    @UnknownInitialization PluginManager this,
                    @NonNull ManagedPlugin plugin,
                    @NonNull PointRegistrations points) {
        Map<ContributionPoint<?>, Consumer<Contribution<?>>> handlers = new HashMap<>();
        for (ContributionPoint<?> point : StandardContributionPoints.ALL) {
            if (point.equals(StandardContributionPoints.RESOURCES)) {
                handlers.put(
                        point,
                        contribution -> {
                            if (!(contribution.implementation() instanceof AutoCloseable resource))
                                throw new IllegalArgumentException(
                                        "Resource contribution required");
                            plugin.registerResource(resource);
                        });
            } else if (point.equals(StandardContributionPoints.CONTRIBUTIONS)) {
                handlers.put(
                        point,
                        contribution -> {
                            if (!(contribution.implementation()
                                    instanceof ProtocolPointDefinition definition))
                                throw new IllegalArgumentException(
                                        "Contribution point definition required");
                            if (!definition.id().value().startsWith(plugin.identity().id() + ":"))
                                throw new IllegalArgumentException(
                                        "Contribution point owner mismatch");
                            var schema = PluginJson.toNode(definition.entrySchema());
                            PluginSchema.check(schema);
                            var definedPoint = definition.point();
                            if (handlers.containsKey(definedPoint)
                                    || definedPoints.putIfAbsent(
                                                    definedPoint,
                                                    new PointDefinition(
                                                            plugin.identity().id(), definition))
                                            != null)
                                throw new IllegalArgumentException("Duplicate contribution point");
                            try {
                                registerContribution(plugin, points, contribution);
                            } catch (RuntimeException failure) {
                                definedPoints.remove(definedPoint);
                                throw failure;
                            }
                        });
            } else if (point.equals(StandardContributionPoints.TOOLS)) {
                handlers.put(
                        point,
                        contribution -> {
                            validateTool(contribution.implementation());
                            registerContribution(plugin, points, contribution);
                        });
            } else if (point.equals(StandardContributionPoints.FRONTEND)) {
                handlers.put(
                        point,
                        contribution -> {
                            validateFrontend(contribution.implementation());
                            registerContribution(plugin, points, contribution);
                        });
            } else if (point.equals(StandardContributionPoints.LISTENERS)) {
                handlers.put(
                        point,
                        contribution -> {
                            if (!(contribution.implementation() instanceof Listener listener))
                                throw new IllegalArgumentException(
                                        "Listener contribution required");
                            listenerPreparation.prepare(listener);
                            registerContribution(plugin, points, contribution);
                        });
            } else {
                handlers.put(
                        point, contribution -> registerContribution(plugin, points, contribution));
            }
        }
        return new HashMap<ContributionPoint<?>, Consumer<Contribution<?>>>(handlers) {
            @Override
            public Consumer<Contribution<?>> get(Object key) {
                if (key == null) return null;
                Consumer<Contribution<?>> standard = handlers.get(key);
                if (standard != null) return standard;
                if (!(key instanceof ContributionPoint<?> point)) return null;
                PointDefinition registered = definedPoints.get(point);
                if (registered == null) return null;
                return entry -> {
                    if (definedPoints.get(point) != registered)
                        throw new IllegalStateException("Contribution point is inactive");
                    if (!(entry.implementation() instanceof JsonValue.ObjectValue value))
                        throw new IllegalArgumentException("JSON contribution required");
                    PluginSchema.validate(
                            PluginJson.toNode(registered.definition().entrySchema()),
                            PluginJson.toNode(value));
                    registerContribution(plugin, points, entry);
                };
            }
        };
    }

    // WHY: ready gates publication, but Checker still needs local guards for this construction
    // callback.
    @SuppressWarnings({"method.invocation", "ConstantValue"})
    private void registerContribution(
            @UnknownInitialization PluginManager this,
            @NonNull ManagedPlugin plugin,
            @NonNull PointRegistrations points,
            @NonNull Contribution<?> contribution) {
        PluginState state = plugin.state();
        if (state == PluginState.STOPPING
                || state == PluginState.CLOSED
                || state == PluginState.FAILED
                || state == PluginState.DECLINED)
            throw new IllegalStateException("Plugin registration is closed");
        if (state != PluginState.ACTIVE || !ready) {
            points.add(contribution);
            return;
        }
        synchronized (this) {
            if (closing) throw new IllegalStateException("Plugin manager is closing");
            points.add(contribution);
            var current = published;
            var registrations = stagedRegistrations;
            if (current == null
                    || registrations == null
                    || registrations.stream().noneMatch(entry -> entry.plugin() == plugin)) return;
            try {
                publish(current.plugins(), registrations, current.disabled(), current.declined());
            } catch (RuntimeException failure) {
                points.remove(contribution);
                throw failure;
            }
        }
    }

    private static void validateTool(Object aspect) {
        if (aspect instanceof RemoteTool portable) {
            PluginSchema.check(PluginJson.toNode(portable.inputSchema()));
            PluginSchema.check(PluginJson.toNode(portable.outputSchema()));
        } else if (aspect instanceof CapabilityTool<?> local
                && (local instanceof AgentTool<?> || local instanceof NativeTool<?>)) {
            ToolSchemaCompiler.compileFromRecord(local.getArgsClass());
        } else throw new IllegalArgumentException("Unsupported plugin tool");
    }

    private static void validateFrontend(Object aspect) {
        if (!(aspect instanceof FrontendContribution frontend))
            throw new IllegalArgumentException("Missing plugin frontend");
        String module = frontend.module();
        if (module.isBlank() || module.length() > 1048576)
            throw new IllegalArgumentException("Invalid frontend module size");
    }

    private static @NonNull ContributionCatalog buildCatalog(
            @NonNull List<Registration> registered) {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) {
            if (point == StandardContributionPoints.TOOLS)
                builder.define(StandardContributionPoints.TOOLS, PluginManager::validateTool);
            else if (point == StandardContributionPoints.FRONTEND)
                builder.define(
                        StandardContributionPoints.FRONTEND, PluginManager::validateFrontend);
            else builder.define(point, ignored -> {});
        }
        var points = new HashSet<ContributionPoint<?>>(StandardContributionPoints.ALL);
        for (var registration : registered) {
            var owner = registration.plugin().identity().id();
            for (var contribution : registration.entries()) {
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
            for (var contribution : registration.entries())
                if (!points.contains(contribution.point())
                        && !(contribution.implementation() instanceof JsonValue.ObjectValue))
                    throw new IllegalArgumentException("Unknown contribution point");
        for (var registration : registered) {
            var identity = registration.plugin().identity();
            builder.stage(
                    new ContributionSource(
                            identity.id(), identity.version(), ContributionSource.Origin.PLUGIN),
                    registration.entries().stream()
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
                                published.plugins().stream()
                                        .noneMatch(plugin -> plugin.identity().id().equals(id)));
    }

    /** Retains the captured contributor until its transaction completes; shutdown drains claims. */
    public synchronized @NonNull ManagedPlugin beginDataCleanup(@NonNull ManagedPlugin runtime) {
        if (closing) throw new IllegalStateException("Plugin manager is closing");
        String id = runtime.identity().id();
        if (published.plugin(id) != runtime)
            throw new IllegalStateException("Plugin data cleanup publication changed");
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

    /** Saves an installed package's desired state without altering this backend's runtime. */
    public synchronized void setEnabledOnNextStart(@NonNull String id, boolean enabled) {
        String canonical = canonicalId(id);
        if (!installedIds.contains(canonical))
            throw new IllegalArgumentException("Plugin is not installed");
        activationStore.setEnabled(canonical, enabled);
    }

    /** Desired activation state for the next backend start; current instances are unchanged. */
    public boolean desiredEnabled(@NonNull String id) {
        return activationStore.desiredEnabled(canonicalId(id), configurations.getDisabled());
    }

    private void publish(
            @NonNull List<ManagedPlugin> nextPlugins,
            @NonNull List<Registration> nextRegistrations,
            @NonNull List<InstalledPluginLoader.DisabledPackage> nextDisabled,
            @NonNull List<DeclinedPlugin> nextDeclined) {
        ContributionCatalog nextCatalog = buildCatalog(nextRegistrations);
        EventListenerRegistry nextEvents =
                preparedEvents(nextCatalog, nextPlugins, listenerPreparation);
        var previous = published;
        var next =
                new PluginRegistry(
                        nextPlugins,
                        nextDisabled,
                        nextDeclined,
                        nextCatalog,
                        nextEvents,
                        aliases,
                        toolNames,
                        installedIds);
        published = next;
        try {
            toolEngine.getObject().reloadPlugins(next);
            llmProviders.getObject().reload(next);
            serviceRegistry.bind(nextCatalog, nextPlugins);
            nextEvents.submit(new ServiceDirectoryChangedEvent());
        } catch (RuntimeException failure) {
            published = previous;
            toolEngine.getObject().reloadPlugins(previous);
            llmProviders.getObject().reload(previous);
            serviceRegistry.bind(previous.catalog(), previous.plugins());
            throw failure;
        }
    }

    /**
     * Prepares event routes bound to the contributing activation in this publication. Each route
     * retains its own lifecycle admission rather than looking up a namespace during dispatch.
     */
    private static @NonNull EventListenerRegistry preparedEvents(
            @NonNull ContributionCatalog catalog,
            @NonNull List<ManagedPlugin> plugins,
            EventListenerRegistry.@NonNull Preparation preparation) {
        Map<@NonNull String, @NonNull ManagedPlugin> indexed = new HashMap<>();
        for (var plugin : plugins) indexed.put(plugin.identity().id(), plugin);
        return EventListenerRegistry.build(catalog, Map.copyOf(indexed), preparation);
    }

    /** Maps a historical plugin id to its canonical identity; unknown ids pass through. */
    private @NonNull String canonicalId(@NonNull String id) {
        return published.canonicalId(id);
    }

    /**
     * Session-less observation masking: threads the text through every ACTIVE plugin's {@code
     * veto:observation-middleware} contribution in catalog order, regardless of session bindings.
     * With no contributor the text is returned unchanged.
     */
    @SuppressWarnings(
            "resource") // WHY: ManagedPlugin handle is owned by this manager, closed in close()
    public @NonNull String applyObservationMiddleware(@NonNull String text) {
        var state = published;
        String result = text;
        for (var entry : state.entries(StandardContributionPoints.OBSERVATION)) {
            var plugin = state.plugin(entry.source().namespace());
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

    /**
     * Drains deletion transactions before closing activations; plugin bodies run outside the
     * monitor.
     */
    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (this) {
            if (!pendingDataCleanups.isEmpty()
                    && TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException(
                        "Cannot close plugins from an active deletion transaction");
            closing = true;
            while (!pendingDataCleanups.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        try {
            for (var plugin : published.plugins().reversed()) {
                serviceRegistry.revoke(plugin.identity().id());
                plugin.close();
            }
        } finally {
            definedPoints.clear();
            lifecycle.shutdown();
        }
    }
}
