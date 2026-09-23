package top.focess.veto.plugin.runtime;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;

/** Real lifecycle and catalog behind a test discovery adapter. */
public final class WorkflowPluginFixture implements AutoCloseable {
    private final @NonNull ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    public final @NonNull ManagedPlugin runtime;
    public final @NonNull PluginManager manager;
    public final @NonNull SessionPlugins sessions;

    public WorkflowPluginFixture(@NonNull List<@NonNull Contribution<?>> contributions)
            throws PluginFailure {
        var implementation =
                new AbstractVetoPlugin() {
                    @Override
                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("fixture.workflow", "1.0.0");
                    }

                    @Override
                    protected @NonNull PluginContributions onInitialize(
                            @NonNull PluginContext context,
                            JsonValue.@NonNull ObjectValue configuration) {
                        return new PluginContributions(contributions);
                    }

                    @Override
                    protected void onStart() {}

                    @Override
                    protected void onClose() {}
                };
        runtime = new ManagedPlugin(implementation, lifecycle);
        runtime.initialize(
                new PluginContext(runtime.identity()), new JsonValue.ObjectValue(Map.of()));
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
        manager = mock(ToolDocs.nonNullClass(PluginManager.class));
        when(manager.catalog()).thenReturn(catalog);
        when(manager.plugins()).thenReturn(List.of(runtime));
        when(manager.plugin("fixture.workflow")).thenReturn(runtime);
        when(manager.toolName(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String id = Objects.requireNonNull(invocation.<String>getArgument(1));
                            return "plugin_fixture_workflow__" + id.substring(id.indexOf(':') + 1);
                        });
        sessions = PluginTestSupport.sessionPlugins(manager);
    }

    @Override
    public void close() {
        runtime.close();
        lifecycle.shutdown();
    }
}
