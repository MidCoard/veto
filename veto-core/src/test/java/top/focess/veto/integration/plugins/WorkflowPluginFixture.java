package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.event.PluginExecutor;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.session.SessionHistoryLoader;

/** Real lifecycle and catalog behind a test discovery adapter. */
public final class WorkflowPluginFixture implements AutoCloseable {
    private final @NonNull ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    public final @NonNull PluginLifecycle runtime;
    public final @NonNull PluginManager manager;
    public final @NonNull SessionPlugins sessions;
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
        runtime = new PluginLifecycle(implementation, lifecycle);
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
        var publication = mock(PluginManager.PublishedState.class);
        when(publication.catalog()).thenReturn(catalog);
        when(publication.plugins()).thenReturn(List.of(runtime));
        when(publication.disabled()).thenReturn(List.of());
        when(publication.declined()).thenReturn(List.of());
        when(publication.plugin("fixture.workflow")).thenReturn(runtime);
        when(manager.snapshot()).thenReturn(publication);
        when(manager.catalog()).thenReturn(catalog);
        when(manager.plugins()).thenReturn(List.of(runtime));
        when(manager.plugin("fixture.workflow")).thenReturn(runtime);
        when(manager.canonicalId(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(manager.toolName(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String id = Objects.requireNonNull(invocation.<String>getArgument(1));
                            return "plugin_fixture_workflow__" + id.substring(id.indexOf(':') + 1);
                        });
        when(manager.toolName(eq(publication), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String id = Objects.requireNonNull(invocation.<String>getArgument(2));
                            return "plugin_fixture_workflow__" + id.substring(id.indexOf(':') + 1);
                        });
        PluginExecutor executor =
                (namespace, body) ->
                        runtime.execute(
                                () -> {
                                    try {
                                        body.run();
                                    } catch (PluginFailure | RuntimeException failure) {
                                        throw failure;
                                    } catch (Exception failure) {
                                        throw new PluginFailure(
                                                PluginFailure.Code.INTERNAL_FAILURE);
                                    }
                                    return true;
                                });
        var events = EventListenerRegistry.build(catalog, executor);
        when(manager.events()).thenReturn(events);
        when(publication.events()).thenReturn(events);
        session.setPluginBindings(
                List.of(
                        new PluginBinding(
                                runtime.identity().id(),
                                runtime.identity().version(),
                                runtime.identity().version())));
        when(repository.findById(anyString())).thenReturn(Optional.of(session));
        sessions = new SessionPlugins(manager, repository, mock(SessionHistoryLoader.class));
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
