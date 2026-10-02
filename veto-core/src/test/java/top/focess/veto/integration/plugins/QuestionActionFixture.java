package top.focess.veto.integration.plugins;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.questions.Question;
import top.focess.veto.controller.PluginFrontendController;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.integration.plugins.storage.ConfigurationStorageFixture;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.UserContext;

/** Actual builtin contributions behind the generic authenticated frontend router. */
public final class QuestionActionFixture implements AutoCloseable {
    public final @NonNull SessionEntity session = new SessionEntity("alice", "session");
    public final @NonNull SessionRepository sessions = mock();
    public final @NonNull SessionPlugins selected;
    public final @NonNull PluginManager manager;
    private final @NonNull Object runtime;
    public final @NonNull MockMvc mvc;
    public final Scope.@NonNull AgentScope scope;

    /** Supplies a distinct persisted row initialized with no selected plugins. */
    public void useUnselectedSession() {
        var unselected = new SessionEntity(session.getOwner(), session.getName());
        ReflectionTestUtils.setField(unselected, "id", session.getId());
        unselected.setPluginBindings(List.of());
        when(sessions.findById(session.getId())).thenReturn(Optional.of(unselected));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(
                        session.getName(), session.getOwner()))
                .thenReturn(Optional.of(unselected));
    }

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
                    public @NonNull PluginStorage bind(@NonNull PluginLifecycle plugin) {
                        var storage = backing.bind(plugin);
                        var invocation = new PluginInvocationContext("alice", session.getId());
                        try {
                            storage.currentSession();
                        } finally {
                            invocation.close();
                        }
                        return storage;
                    }

                    public @NonNull String authorizeSession(
                            @NonNull PluginStorage storage,
                            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> scope) {
                        return backing.authorizeSession(storage, scope);
                    }

                    public @NonNull String authorizeUser(
                            @NonNull PluginStorage storage,
                            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> scope) {
                        return backing.authorizeUser(storage, scope);
                    }

                    public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> transferUser(
                            @NonNull PluginStorage caller,
                            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> scope,
                            @NonNull PluginStorage provider) {
                        return backing.transferUser(caller, scope, provider);
                    }

                    public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope>
                            transferSession(
                                    @NonNull PluginStorage caller,
                                    PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> scope,
                                    @NonNull PluginStorage provider) {
                        return backing.transferSession(caller, scope, provider);
                    }
                };
        granted.put(PluginStorageFactory.class, storageFactory);
        var configuration = new PluginConfigurations();
        configuration.setToolNames(Map.of("top.focess.builtin:ask_user", "ask_user"));
        manager =
                new PluginManager(
                        PluginTestSupport.pluginPackages(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(
                                PluginTestSupport.configurationServices(
                                        new PluginHostServices(granted))),
                        configuration);
        runtime =
                manager.catalog().entries(StandardContributionPoints.LISTENERS).stream()
                        .map(entry -> entry.implementation())
                        .filter(
                                value ->
                                        value.getClass()
                                                .getName()
                                                .equals(
                                                        "top.focess.veto.builtin.questions.QuestionRuntime"))
                        .findFirst()
                        .orElseThrow();
        scope = new Scope.AgentScope("alice", session.getId(), "agent");
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("session", "alice"))
                .thenReturn(Optional.of(session));
        selected = spy(new SessionPlugins(manager, sessions));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        session.setPluginBindings(
                List.of(new PluginBinding("top.focess.builtin", "1.0.100", "1.0.100")));
        SessionAgentRegistry agents = mock(SessionAgentRegistry.class);
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
                scope.owner(), scope.session(), scope.agent(), "request", call);
    }

    public int pendingCount() {
        try {
            Object result =
                    runtime.getClass()
                            .getMethod("pendingFor", Scope.AgentScope.class)
                            .invoke(runtime, scope);
            return ((List<?>) Nullness.requireNonNull(result)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public record AnswerSnapshot(@NonNull Map<String, String> answers, boolean cancelled) {}

    public @NonNull CompletableFuture<AnswerSnapshot> registerQuestions(
            @NonNull String callId, @NonNull List<@NonNull Question> questions) {
        try {
            ClassLoader loader = Nullness.requireNonNull(runtime.getClass().getClassLoader());
            Class<?> optionType = loader.loadClass("top.focess.veto.builtin.questions.Option");
            Class<?> questionType = loader.loadClass("top.focess.veto.builtin.questions.Question");
            var optionConstructor = optionType.getConstructor(String.class, String.class);
            var questionConstructor =
                    questionType.getConstructor(
                            String.class, String.class, String.class, List.class);
            List<Object> converted = new ArrayList<>();
            for (Question question : questions) {
                List<Object> options = new ArrayList<>();
                for (var option : question.options())
                    options.add(
                            optionConstructor.newInstance(option.label(), option.description()));
                converted.add(
                        questionConstructor.newInstance(
                                question.header(), question.id(), question.question(), options));
            }
            var register =
                    runtime.getClass()
                            .getDeclaredMethod("register", PluginHost.Invocation.class, List.class);
            register.setAccessible(true); // Test-only access to the builtin's internal rendezvous.
            var result =
                    (CompletableFuture<?>) register.invoke(runtime, invocation(callId), converted);
            return Nullness.requireNonNull(result)
                    .thenApply(
                            answer -> {
                                try {
                                    Object batch = Nullness.requireNonNull(answer);
                                    @SuppressWarnings("unchecked")
                                    Map<String, String> values =
                                            (Map<String, String>)
                                                    batch.getClass()
                                                            .getMethod("answers")
                                                            .invoke(batch);
                                    boolean cancelled =
                                            (boolean)
                                                    Nullness.requireNonNull(
                                                            batch.getClass()
                                                                    .getMethod("cancelled")
                                                                    .invoke(batch));
                                    return new AnswerSnapshot(
                                            Nullness.requireNonNull(values), cancelled);
                                } catch (ReflectiveOperationException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
