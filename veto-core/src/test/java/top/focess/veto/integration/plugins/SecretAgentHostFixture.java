package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.ExecutionSecurity;
import top.focess.veto.vault.KeysteadVault;

/** Scripted local predictions with real prepared-tool execution for primary-context tests. */
public final class SecretAgentHostFixture implements PluginAgentHostFactory {
    private final @NonNull Supplier<@NonNull String> primary;
    private final @NonNull Supplier<@NonNull ToolEngine> engines;
    private final @NonNull List<@NonNull String> secrets;
    private final @NonNull Gateway gateway;

    public SecretAgentHostFixture(
            @NonNull String primary,
            @NonNull Workspace workspace,
            @NonNull Supplier<@NonNull ToolEngine> engines,
            @NonNull List<@NonNull String> secrets) {
        this(() -> primary, workspace, engines, secrets);
    }

    public SecretAgentHostFixture(
            @NonNull Supplier<@NonNull String> primary,
            @NonNull Workspace workspace,
            @NonNull Supplier<@NonNull ToolEngine> engines,
            @NonNull List<@NonNull String> secrets) {
        this.primary = primary;
        this.engines = engines;
        this.secrets = List.copyOf(secrets);
        gateway =
                new Gateway(
                        workspace,
                        new DangerComputation(),
                        SlmScreeningProvider.unavailable(),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty(),
                        new ReadHistory());
    }

    /** Keeps the real plugin and tool policy while scripting only local model predictions. */
    public static @NonNull PluginManager manager(
            @NonNull String primary, @NonNull List<@NonNull String> secrets) throws IOException {
        var engine = new AtomicReference<ToolEngine>();
        var services =
                services(
                        new SecretAgentHostFixture(
                                primary,
                                Workspace.single(
                                        Path.of("work/tmp/secret-corpus-10k").toAbsolutePath(),
                                        PathMode.VIRTUAL),
                                () -> Nullness.requireNonNull(engine.get()),
                                secrets));
        var plugins = PluginTestSupport.manager(services);
        try {
            var context = mock(ApplicationContext.class);
            when(context.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
            when(context.getBeansOfType(PluginManager.class))
                    .thenReturn(Map.of("plugins", plugins));
            var tools =
                    new ToolEngineImpl(
                            new ObjectMapper(),
                            List.of(),
                            context,
                            PluginTestSupport.sessionPlugins(plugins),
                            PluginTestSupport.eventManager(plugins));
            tools.afterSingletonsInstantiated();
            engine.set(tools);
            return plugins;
        } catch (Throwable failure) {
            try {
                plugins.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static @NonNull PluginHostServices services(@NonNull PluginAgentHostFactory hosts) {
        var vault = mock(KeysteadVault.class);
        when(vault.isUnlocked(any(UUID.class))).thenReturn(true);
        when(vault.createSecureNoteIfAbsent(any(UUID.class), anyString(), anyMap(), anyString()))
                .thenAnswer(invocation -> "cred_" + UUID.randomUUID().toString().replace("-", ""));
        var services =
                new HashMap<Class<?>, Object>(
                        new HostResourceConfiguration()
                                .pluginHostServices(
                                        PluginTestSupport.providerOf(vault),
                                        PluginTestSupport.providerOf(null))
                                .services());
        services.put(PluginAgentHostFactory.class, hosts);
        return new PluginHostServices(services);
    }

    public @NonNull AgentHost bind(@NonNull ManagedPlugin plugin, @NonNull PluginStorage storage) {
        return grant -> {
            storage.session(grant);
            var owner = grant.scope();
            return new AgentHost.Session() {
                public @NonNull String id() {
                    return owner.session();
                }

                public @NonNull String primaryAgentId() {
                    return primary.get();
                }

                public AgentHost.@NonNull Child open(
                        @NonNull String id, @NonNull String parent, @NonNull AgentProfile profile) {
                    if (!primary.get().equals(parent) || profile.model() == null)
                        throw new AssertionError("Expected a local child of the primary agent");
                    return new AgentHost.Child() {
                        private Thread worker;

                        public @NonNull String id() {
                            return id;
                        }

                        public @NonNull AgentState state() {
                            return AgentState.IDLE;
                        }

                        public AgentHost.@NonNull Request submit(@NonNull String text) {
                            var result = new CompletableFuture<AgentResult>();
                            var settled = new CompletableFuture<Boolean>();
                            worker =
                                    Thread.ofVirtual()
                                            .name("secret-model-fixture")
                                            .start(
                                                    () -> {
                                                        var security =
                                                                ExecutionSecurity.open(
                                                                        owner.userId());
                                                        try (security) {
                                                            var engine = engines.get();
                                                            var name =
                                                                    profile.tools()
                                                                            .iterator()
                                                                            .next();
                                                            var definition =
                                                                    Nullness.requireNonNull(
                                                                            engine
                                                                                    .resolveDefinition(
                                                                                            name));
                                                            for (var value : secrets) {
                                                                if (!text.contains(value)) continue;
                                                                var call =
                                                                        new ToolCall(
                                                                                name,
                                                                                Map.of(
                                                                                        "value",
                                                                                        value,
                                                                                        "visibility",
                                                                                        "PRIVATE",
                                                                                        "service",
                                                                                        "github",
                                                                                        "label",
                                                                                        "Repository"),
                                                                                UUID.randomUUID()
                                                                                        .toString());
                                                                var invocation =
                                                                        new PluginHost.Invocation(
                                                                                owner.userId(),
                                                                                owner.session(),
                                                                                id,
                                                                                null,
                                                                                call.callId());
                                                                var prepared =
                                                                        engine.prepare(
                                                                                call,
                                                                                definition,
                                                                                invocation);
                                                                var screened =
                                                                        (GatewayResult.Screened)
                                                                                gateway.screen(
                                                                                        call,
                                                                                        definition,
                                                                                        null,
                                                                                        null,
                                                                                        null,
                                                                                        null,
                                                                                        prepared);
                                                                var session =
                                                                        UUID.fromString(
                                                                                owner.session());
                                                                var permit =
                                                                        gateway.revalidateExecution(
                                                                                        call,
                                                                                        definition,
                                                                                        screened
                                                                                                .executionPermit())
                                                                                .withCaller(
                                                                                        id,
                                                                                        owner
                                                                                                .userId(),
                                                                                        session);
                                                                ToolCallContextHolder.set(
                                                                        new ToolCallContext(
                                                                                id,
                                                                                owner.userId(),
                                                                                session,
                                                                                ToolResultPresentationMode
                                                                                        .BASIC,
                                                                                permit));
                                                                var imported =
                                                                        engine.execute(
                                                                                call, definition);
                                                                if (!imported.success())
                                                                    throw new AssertionError(
                                                                            imported.content());
                                                            }
                                                            result.complete(
                                                                    AgentResult.success(
                                                                            "DONE", Map.of()));
                                                        } catch (Throwable failure) {
                                                            result.completeExceptionally(failure);
                                                        } finally {
                                                            ToolCallContextHolder.clear();
                                                            settled.complete(true);
                                                        }
                                                    });
                            return new AgentHost.Request() {
                                public @NonNull String id() {
                                    return "scripted-request";
                                }

                                public @NonNull CompletableFuture<AgentResult> result() {
                                    return result.copy();
                                }

                                public @NonNull CompletableFuture<Boolean> settled() {
                                    return settled.copy();
                                }

                                public boolean cancel(@NonNull Duration timeout)
                                        throws InterruptedException {
                                    var thread = worker;
                                    if (thread == null) return true;
                                    thread.interrupt();
                                    return thread.join(timeout);
                                }
                            };
                        }

                        public void close() {
                            var thread = worker;
                            if (thread != null && thread.isAlive()) thread.interrupt();
                        }

                        public boolean awaitTermination(@NonNull Duration timeout)
                                throws InterruptedException {
                            var thread = worker;
                            return thread == null || thread.join(timeout);
                        }
                    };
                }
            };
        };
    }
}
