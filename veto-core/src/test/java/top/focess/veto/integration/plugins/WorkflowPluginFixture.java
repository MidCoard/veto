package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.event.EventManager;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/** Real lifecycle and catalog behind a test discovery adapter. */
public final class WorkflowPluginFixture implements AutoCloseable {
    private final @NonNull ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    public final @NonNull ManagedPlugin runtime;
    public final @NonNull PluginManager manager;
    public final @NonNull SessionPlugins sessions;
    public final @NonNull EventManager events;
    public final @NonNull SessionEntity session = new SessionEntity("owner", "session");
    private final @NonNull SessionRepository repository = mock(SessionRepository.class);

    public WorkflowPluginFixture(@NonNull List<@NonNull Contribution<?>> contributions)
            throws PluginFailure {
        var implementation =
                new VetoPlugin() {
                    @Override
                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("fixture.workflow", "1.0.0");
                    }

                    @Override
                    public void start() {}

                    @Override
                    public void close() {}
                };
        runtime = new ManagedPlugin(implementation, lifecycle);
        runtime.construct(
                new PluginContext(
                        runtime.identity(),
                        () -> {},
                        () -> {
                            throw new IllegalStateException(
                                    "Plugin context is not bound to a lifecycle owner");
                        },
                        Map.of(),
                        Map.of()),
                new JsonValue.ObjectValue(Map.of()));
        runtime.start();
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
        var catalog =
                builder.stage(
                                new ContributionSource(
                                        "fixture.workflow",
                                        "1.0.0",
                                        ContributionSource.Origin.PLUGIN),
                                contributions)
                        .freeze();
        manager = mock(PluginManager.class);
        var events =
                EventListenerRegistry.build(
                        catalog,
                        Map.of(runtime.identity().id(), runtime),
                        new EventListenerRegistry.Preparation());
        var publication =
                spy(
                        new PluginRegistry(
                                List.of(runtime),
                                List.of(),
                                List.of(),
                                catalog,
                                events,
                                Map.of(),
                                Map.of(),
                                Set.of()));
        when(manager.registry()).thenReturn(publication);
        session.setPluginBindings(
                List.of(
                        new PluginBinding(
                                runtime.identity().id(),
                                runtime.identity().version(),
                                runtime.identity().version())));
        when(repository.findById(anyString())).thenReturn(Optional.of(session));
        sessions = new SessionPlugins(manager, repository);
        this.events = new EventManager(manager, sessions);
        var registry = new PluginServiceRegistry((caller, provider) -> true);
        try {
            registry.bind(catalog, List.of(runtime));
        } catch (RuntimeException failure) {
            runtime.close();
            lifecycle.shutdown();
            throw failure;
        }
        when(manager.services()).thenReturn(registry.forHost());
    }

    /** Supplies a separately initialized unselected row without mutating immutable session pins. */
    public void useUnselectedSession() {
        var unselected = new SessionEntity(session.getOwner(), session.getName());
        ReflectionTestUtils.setField(unselected, "id", session.getId());
        unselected.setPluginBindings(List.of());
        when(repository.findById(anyString())).thenReturn(Optional.of(unselected));
    }

    /** Restores the original pinned row for subsequent lifecycle-admission assertions. */
    public void restoreSelectedSession() {
        when(repository.findById(anyString())).thenReturn(Optional.of(session));
    }

    @Override
    public void close() {
        runtime.close();
        lifecycle.shutdown();
    }
}
