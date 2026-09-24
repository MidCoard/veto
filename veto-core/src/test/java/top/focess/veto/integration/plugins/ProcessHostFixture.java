package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
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
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.ProcessRuntime;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.integration.plugins.storage.ConfigurationStorageFixture;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.sandbox.*;

/** Real process host and builtin tools with explicit test-only session membership. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class ProcessHostFixture implements AutoCloseable {
    public final UUID session = UUID.randomUUID();
    public final String owner = "test-owner";
    public final String agent = "test-agent";
    public final UUID user = UUID.randomUUID();
    public final AtomicBoolean admitted = new AtomicBoolean(true);
    public final SessionAgentRegistry agents =
            mock(ToolDocs.nonNullClass(SessionAgentRegistry.class));
    public final ManagedPlugin plugin;
    public final ToolEngineImpl engine;
    public final ProcessHost host;
    public final ProcessRuntime feature;
    private final java.util.concurrent.ExecutorService lifecycle =
            Executors.newSingleThreadExecutor();

    public ProcessHostFixture(
            ConstrainedSubprocessSubstrate substrate,
            List<NativeTool<?>> extra,
            boolean background) {
        try {
            var implementation =
                    new AbstractVetoPlugin() {
                        public PluginIdentity identity() {
                            return new PluginIdentity("fixture.process", "1.0.0");
                        }

                        protected PluginContributions onInitialize(
                                PluginContext context, JsonValue.ObjectValue config) {
                            return new PluginContributions(List.of());
                        }

                        protected void onStart() {}

                        protected void onClose() {}
                    };
            plugin = new ManagedPlugin(implementation, lifecycle);
            @NonNull PluginStorage storage = mock();
            @NonNull PluginStorageFactory scopes = mock();
            var scope = new PluginStorage.SessionScope("issued", owner, session.toString());
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
            @NonNull VetoAgent live = mock();
            when(live.id()).thenReturn(agent);
            when(agents.agents(session))
                    .thenReturn(List.of(new SessionAgentRegistry.Entry(session, null, null, live)));
            host =
                    new PluginProcessHosts(new SandboxManager(substrate), scopes, agents)
                            .bind(plugin, storage);
            PluginHost effects =
                    new PluginHost() {
                        public Invocation invocation(String tool) {
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
                            Map.of(
                                    ToolDocs.nonNullClass(ProcessHost.class),
                                    host,
                                    ToolDocs.nonNullClass(PluginHost.class),
                                    effects));
            feature = new ProcessRuntime(context);
            plugin.initialize(context, new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            List<Contribution<?>> entries = new ArrayList<>();
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.NATIVE_TOOLS,
                            "run_command",
                            new RunCommandTool(feature.execution("run_command"))));
            if (background) {
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.NATIVE_TOOLS,
                                "run_task",
                                new RunTaskTool(feature.execution("run_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.NATIVE_TOOLS,
                                "view_task",
                                new ViewTaskTool(feature.control("view_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.NATIVE_TOOLS,
                                "input_task",
                                new InputTaskTool(feature.control("input_task"))));
                entries.add(
                        Contribution.of(
                                StandardContributionPoints.NATIVE_TOOLS,
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
            @NonNull PluginManager manager = mock();
            when(manager.catalog()).thenReturn(catalog);
            when(manager.plugins()).thenReturn(List.of(plugin));
            when(manager.plugin(plugin.identity().id())).thenReturn(plugin);
            when(manager.toolName(anyString(), anyString()))
                    .thenAnswer(
                            call -> {
                                String id = call.getArgument(1);
                                if (id == null) throw new AssertionError();
                                return id.substring(id.indexOf(':') + 1);
                            });
            @NonNull SessionPlugins selected = mock();
            when(selected.includes(anyString(), anyString())).thenAnswer(call -> admitted.get());
            @NonNull ApplicationContext app = mock();
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

    public static PluginHostServices services(SandboxManager sandbox) {
        var scopes = new ConfigurationStorageFixture();
        PluginHost effects =
                new PluginHost() {
                    public Invocation invocation(String tool) {
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

                    public void wake(String owner, String session, String agent) {}

                    public void invalidate(String session, String resource) {}
                };
        return new PluginHostServices(
                Map.of(
                        ToolDocs.nonNullClass(PluginStorageFactory.class),
                        scopes,
                        ToolDocs.nonNullClass(PluginProcessHostFactory.class),
                        new PluginProcessHosts(sandbox, scopes, new SessionAgentRegistry()),
                        ToolDocs.nonNullClass(PluginHost.class),
                        effects));
    }

    public ToolExecutionPermit permit(
            ToolCall call, ToolDefinition definition, Workspace workspace) {
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
