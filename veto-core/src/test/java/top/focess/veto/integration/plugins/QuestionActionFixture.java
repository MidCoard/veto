package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.FrontendContribution.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.questions.QuestionRuntime;
import top.focess.veto.controller.PluginFrontendController;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.integration.plugins.storage.ConfigurationStorageFixture;
import top.focess.veto.integration.plugins.storage.PluginInvocationScope;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.vault.UserContext;

/** Actual builtin contributions behind the generic authenticated frontend router. */
public final class QuestionActionFixture implements AutoCloseable {
    public final @NonNull SessionEntity session = new SessionEntity("alice", "session");
    public final @NonNull SessionRepository sessions = mock();
    public final @NonNull SessionPlugins selected = mock();
    public final @NonNull PluginManager manager;
    public final @NonNull QuestionRuntime runtime;
    public final @NonNull MockMvc mvc;
    public final @NonNull Scope scope;

    public QuestionActionFixture() throws IOException {
        var config = new PluginHostConfiguration();
        var services =
                config.runtimeHostServices(
                        PluginTestSupport.providerOf(null),
                        PluginTestSupport.providerOf(null),
                        PluginTestSupport.providerOf(null),
                        PluginTestSupport.providerOf(mock()));
        Map<@NonNull Class<?>, @NonNull Object> granted = new HashMap<>(services.services());
        var backing = new ConfigurationStorageFixture();
        PluginStorageFactory storageFactory =
                new PluginStorageFactory() {
                    public @NonNull PluginStorage bind(@NonNull ManagedPlugin plugin) {
                        var storage = backing.bind(plugin);
                        var invocation = new PluginInvocationScope("alice", session.getId());
                        try {
                            storage.currentSession();
                        } finally {
                            invocation.close();
                        }
                        return storage;
                    }

                    public @NonNull String authorizeSession(
                            @NonNull PluginStorage storage,
                            PluginStorage.@NonNull SessionScope scope) {
                        return backing.authorizeSession(storage, scope);
                    }
                };
        granted.put(ToolDocs.nonNullClass(PluginStorageFactory.class), storageFactory);
        var configuration = new PluginConfigurations();
        configuration.setToolNames(Map.of("top.focess.builtin:ask_user", "ask_user"));
        manager =
                new PluginManager(
                        "",
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(
                                PluginTestSupport.configurationServices(
                                        new PluginHostServices(granted))),
                        configuration);
        runtime =
                manager.catalog().entries(StandardContributionPoints.SESSION_LIFECYCLE).stream()
                        .map(entry -> entry.implementation())
                        .filter(value -> value instanceof QuestionRuntime)
                        .map(value -> (QuestionRuntime) value)
                        .findFirst()
                        .orElseThrow();
        scope = new Scope("alice", session.getId(), "agent");
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("session", "alice"))
                .thenReturn(Optional.of(session));
        when(selected.bindings(session.getId()))
                .thenReturn(List.of(new PluginBinding("top.focess.builtin", "1.0.100", "1.0.100")));
        @NonNull SessionAgentRegistry agents = mock();
        when(agents.records(UUID.fromString(session.getId())))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.AgentSummary(
                                        "agent", "Agent", null, null, null, null, false, null, null,
                                        null, null, true, null, null)));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PluginFrontendController(
                                        new RequestAuthorization(user -> false),
                                        sessions,
                                        selected,
                                        manager,
                                        agents))
                        .build();
    }

    public PluginHost.@NonNull Invocation invocation(@NonNull String call) {
        return new PluginHost.Invocation(
                scope.ownerId(), scope.sessionId(), scope.agentId(), "request", call);
    }

    public @NonNull Map<String, Object> action(
            @NonNull String action, @NonNull Map<String, ?> args) {
        return Map.of(
                "moduleId",
                "top.focess.builtin:questions",
                "agentId",
                "agent",
                "action",
                action,
                "arguments",
                args);
    }

    @Override
    public void close() {
        UserContext.clear();
        manager.close();
    }
}
