package top.focess.veto.integration.plugins;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.integration.plugins.storage.ConfigurationStorageFixture;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.session.SessionHistoryLoader;

/** Shared wiring for plugin-backed tests using installable manifest packages. */
public final class PluginTestSupport {
    private PluginTestSupport() {}

    /** A provider yielding the given value (or none), for ObjectProvider-based wiring in tests. */
    public static <T> @NonNull ObjectProvider<T> providerOf(@Nullable T value) {
        return new ObjectProvider<>() {
            @Override
            public @NonNull T getObject() {
                var result = value;
                if (result == null) throw new NoSuchBeanDefinitionException("empty test provider");
                return result;
            }

            @Override
            public @Nullable T getIfAvailable() {
                return value;
            }

            @Override
            public @NonNull Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public @NonNull Stream<T> orderedStream() {
                return stream();
            }

            @Override
            public @NonNull Iterator<T> iterator() {
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }
        };
    }

    /** Starts a manager whose plugins come from packaged plugin.json manifests. */
    public static @NonNull PluginManager manager() throws IOException {
        return manager(null);
    }

    public static @NonNull String pluginPackages() {
        String configured = System.getProperty("veto.test.plugin-packages");
        if (configured == null || !Files.isDirectory(Path.of(configured)))
            throw new IllegalStateException("Packaged test plugins are unavailable");
        return configured;
    }

    public static @NonNull PluginManager manager(PluginHostServices services) throws IOException {
        return new PluginManager(
                pluginPackages(),
                "",
                false,
                5000,
                providerOf(configurationServices(services)),
                new PluginConfigurations());
    }

    /** Only configuration storage is provided; accidental child execution fails visibly. */
    public static @NonNull PluginHostServices configurationServices(PluginHostServices services) {
        Map<@NonNull Class<?>, @NonNull Object> merged = new HashMap<>();
        merged.put(PluginStorageFactory.class, new ConfigurationStorageFixture());
        merged.put(
                PluginHost.class,
                new PluginHost() {
                    @Override
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new IllegalStateException("No test tool invocation");
                    }

                    @Override
                    public void wake(
                            @NonNull String owner,
                            @NonNull String sessionId,
                            @NonNull String agentId) {}

                    @Override
                    public void invalidate(@NonNull String sessionId, @NonNull String resource) {}
                });
        PluginAgentHostFactory hosts =
                (plugin, storage) ->
                        scope -> {
                            storage.session(scope);
                            return new AgentHost.Session() {
                                public @NonNull String id() {
                                    return scope.scope().session();
                                }

                                public AgentHost.@NonNull Child open(
                                        @NonNull String id,
                                        @NonNull String parent,
                                        @NonNull AgentProfile profile) {
                                    throw new UnsupportedOperationException(
                                            "Configuration fixture cannot execute children");
                                }
                            };
                        };
        merged.put(PluginAgentHostFactory.class, hosts);
        if (services != null) merged.putAll(services.services());
        return new PluginHostServices(merged);
    }

    /**
     * Session selection backed by a stub repository; every session selects every discovered plugin.
     */
    public static @NonNull SessionPlugins sessionPlugins(@NonNull PluginManager manager) {
        SessionRepository sessions = mock(SessionRepository.class);
        SessionHistoryLoader history = mock(SessionHistoryLoader.class);
        var entity = new SessionEntity("owner", "session");
        entity.setPluginBindings(
                manager.plugins().stream()
                        .map(
                                plugin ->
                                        new PluginBinding(
                                                plugin.identity().id(),
                                                plugin.identity().version(),
                                                plugin.identity().version()))
                        .toList());
        when(sessions.findById(anyString())).thenReturn(Optional.of(entity));
        return new SessionPlugins(manager, sessions, history);
    }

    /** Dispatches the selected plugins' text event outside the session-binding machinery. */
    public static @NonNull String protect(
            @NonNull PluginManager manager,
            BeforeTextCommitEvent.@NonNull Phase phase,
            Scope.@NonNull AgentScope scope,
            @NonNull String sourceId,
            @NonNull String text)
            throws PluginFailure {
        var event =
                new BeforeTextCommitEvent(
                        scope.owner(),
                        scope.session(),
                        scope.agent(),
                        () -> false,
                        phase,
                        sourceId,
                        text);
        manager.events()
                .submit(
                        event,
                        manager.plugins().stream()
                                .map(plugin -> plugin.identity().id())
                                .collect(java.util.stream.Collectors.toSet()));
        if (event.isPrevent()) throw new IllegalStateException("Text publication prevented");
        return event.text();
    }

    /** Reveals a reference through the plugin's frontend "show" action; empty when unavailable. */
    public static @NonNull Optional<String> reveal(
            @NonNull PluginManager manager,
            Scope.@NonNull AgentScope scope,
            @NonNull String reference)
            throws PluginFailure {
        var entry =
                manager.catalog().entries(StandardContributionPoints.FRONTEND).stream()
                        .filter(
                                candidate ->
                                        candidate
                                                .source()
                                                .namespace()
                                                .equals("top.focess.secret-protection"))
                        .findFirst()
                        .orElseThrow();
        JsonValue result =
                manager.plugin(entry.source().namespace())
                        .execute(
                                () ->
                                        entry.implementation()
                                                .handler()
                                                .handle(
                                                        new Scope.AgentScope(
                                                                scope.owner(),
                                                                scope.session(),
                                                                scope.agent()),
                                                        "show",
                                                        new JsonValue.ObjectValue(
                                                                Map.of(
                                                                        "reference",
                                                                        new JsonValue.StringValue(
                                                                                reference)))));
        return result instanceof JsonValue.StringValue value
                ? Optional.of(value.value())
                : Optional.empty();
    }
}
