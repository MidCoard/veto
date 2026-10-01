package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.*;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.ProcessRuntime;
import top.focess.veto.builtin.process.TaskEvents;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.integration.plugins.storage.ConfigurationStorageFixture;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.sandbox.*;

/** Real process host and builtin tools with explicit test-only session membership. */
public final class ProcessHostFixture implements AutoCloseable {
    public final @NonNull UUID session = UUID.randomUUID();
    public final @NonNull String owner = "test-owner";
    public final @NonNull String agent = "test-agent";
    public final @NonNull UUID user = UUID.randomUUID();
    public final @NonNull AtomicBoolean admitted = new AtomicBoolean(true);
    public final @NonNull SessionAgentRegistry agents = mock(SessionAgentRegistry.class);
    public final @NonNull PluginLifecycle plugin;
    public final @NonNull ToolEngineImpl engine;
    public final @NonNull ProcessHost host;
    public final @NonNull ProcessRuntime feature;
    private final @NonNull ExecutorService lifecycle = Executors.newSingleThreadExecutor();

    public ProcessHostFixture(
            @NonNull ConstrainedSubprocessSubstrate substrate,
            @NonNull List<@NonNull NativeTool<?>> extra,
            boolean background) {
        try {
            var implementation =
                    new VetoPlugin() {

                        public @NonNull PluginIdentity identity() {
                            return new PluginIdentity("fixture.process", "1.0.0");
                        }

                        public void start() {}

                        public void close() {}
                    };
            plugin = new PluginLifecycle(implementation, lifecycle);
            PluginStorage storage = mock(PluginStorage.class);
            PluginStorageFactory scopes = mock(PluginStorageFactory.class);
            var scope =
                    new PluginStorage.Grant<>(
                            "issued", new Scope.SessionScope(user.toString(), session.toString()));
            when(storage.currentSession())
                    .thenAnswer(
                            call -> {
                                var current = ToolCallContextHolder.get();
                                if (current == null
                                        || !owner.equals(current.owner())
                                        || !session.equals(current.sessionId()))
                                    throw new SecurityException("Fixture scope mismatch");
                                return scope;
                            });
            when(scopes.authorizeSession(storage, scope))
                    .thenAnswer(
                            call -> {
                                if (!admitted.get()) throw new SecurityException("Scope revoked");
                                return owner;
                            });
            VetoAgent live = mock(VetoAgent.class);
            when(live.id()).thenReturn(agent);
            when(agents.agents(session))
                    .thenReturn(List.of(new SessionAgentRegistry.Entry(session, null, null, live)));
            host =
                    new PluginProcessHosts(new SandboxManager(substrate), scopes, agents)
                            .bind(plugin, storage);
            PluginHost effects =
                    new PluginHost() {
                        public @NonNull Invocation invocation(@NonNull String tool) {
                            var active = ToolCallContextHolder.get();
                            if (active == null) throw new SecurityException("No invocation");
                            var current =
                                    CapabilityAccess.require(
                                            active.executionPermit().capability(), tool);
                            if (!plugin.bindingId()
                                            .equals(current.executionPermit().remoteServerName())
                                    || !admitted.get())
                                throw new SecurityException("Wrong plugin invocation");
                            return new Invocation(
                                    owner,
                                    session.toString(),
                                    agent,
                                    current.requestId(),
                                    current.executionPermit().callId());
                        }

                        public void wake(String owner, String session, String agent) {}

                        public void invalidate(String session, String resource) {}
                    };
            var context =
                    new PluginContext(
                            plugin.identity(),
                            () -> {},
                            plugin::state,
                            Map.of(ProcessHost.class, host, PluginHost.class, effects),
                            Map.of());
            feature = new ProcessRuntime(context, mock(TaskEvents.class));
            plugin.construct(context, new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            List<Contribution<?>> entries = new ArrayList<>();
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.TOOLS,
                            "run_command",
                            new RunCommandTool(feature.execution("run_command"))));
            if (background) {
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "run_task",
                                new RunTaskTool(feature.execution("run_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "view_task",
                                new ViewTaskTool(feature.control("view_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "input_task",
                                new InputTaskTool(feature.control("input_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "stop_task",
                                new StopTaskTool(feature.control("stop_task"))));
            }
            var builder = new ContributionCatalog.Builder();
            for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
            var catalog =
                    builder.stage(
                                    new ContributionSource(
                                            plugin.identity().id(),
                                            "1.0.0",
                                            ContributionSource.Origin.PLUGIN),
                                    entries)
                            .freeze();
            PluginManager manager = mock(PluginManager.class);
            var publication = mock(PluginManager.PublishedState.class);
            when(publication.catalog()).thenReturn(catalog);
            when(publication.plugins()).thenReturn(List.of(plugin));
            when(publication.disabled()).thenReturn(List.of());
            when(publication.declined()).thenReturn(List.of());
            when(publication.plugin(plugin.identity().id())).thenReturn(plugin);
            when(manager.snapshot()).thenReturn(publication);
            when(manager.catalog()).thenReturn(catalog);
            when(manager.plugins()).thenReturn(List.of(plugin));
            when(manager.plugin(plugin.identity().id())).thenReturn(plugin);
            when(manager.toolName(eq(publication), anyString(), anyString()))
                    .thenAnswer(
                            call -> {
                                String id = call.getArgument(2);
                                if (id == null) throw new AssertionError();
                                return id.substring(id.indexOf(':') + 1);
                            });
            SessionPlugins selected = mock(SessionPlugins.class);
            when(selected.includes(anyString(), anyString())).thenAnswer(call -> admitted.get());
            ApplicationContext app = mock(ApplicationContext.class);
            when(app.getBeansOfType(PluginManager.class)).thenReturn(Map.of("plugins", manager));
            when(app.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
            engine = new ToolEngineImpl(new ObjectMapper(), extra, app);
            engine.attachSessionPlugins(selected);
            ReflectionTestUtils.invokeMethod(engine, "init");
        } catch (Exception failure) {
            lifecycle.shutdown();
            throw new IllegalStateException(failure);
        }
    }

    public static @NonNull PluginHostServices services(@NonNull SandboxManager sandbox) {
        var scopes = new ConfigurationStorageFixture();
        PluginHost effects =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        var current = ToolCallContextHolder.get();
                        if (current == null) throw new SecurityException("No invocation");
                        CapabilityAccess.require(current.executionPermit().capability(), tool);
                        var owner = current.owner();
                        var session = current.sessionId();
                        if (owner == null || session == null)
                            throw new SecurityException("No owned session");
                        return new Invocation(
                                owner,
                                session.toString(),
                                current.agentId(),
                                current.requestId(),
                                current.executionPermit().callId());
                    }

                    public void wake(
                            @NonNull String owner,
                            @NonNull String session,
                            @NonNull String agent) {}

                    public void invalidate(@NonNull String session, @NonNull String resource) {}
                };
        return new PluginHostServices(
                Map.of(
                        PluginStorageFactory.class,
                        scopes,
                        PluginProcessHostFactory.class,
                        new PluginProcessHosts(sandbox, scopes, new SessionAgentRegistry()),
                        PluginHost.class,
                        effects));
    }

    public @NonNull ToolExecutionPermit permit(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Workspace workspace) {
        var prepared =
                engine.prepare(
                        call,
                        definition,
                        new PluginHost.Invocation(
                                owner, session.toString(), agent, null, call.callId()));
        var permit =
                ToolExecutionPermit.capture(call, definition, workspace)
                        .withCaller(agent, user, owner, session);
        return prepared == null ? permit : permit.withPreparation(prepared);
    }

    public void close() {
        feature.tasks().close();
        plugin.close();
        lifecycle.shutdown();
    }
}
