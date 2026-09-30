package top.focess.veto.integration.plugins.storage;

import top.focess.veto.api.plugin.PluginScope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.controller.PluginFrontendController;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.UserContext;
import top.focess.veto.vault.UserEntity;

class ScopedPluginStorageTest {
    private @NonNull LocalContainerEntityManagerFactoryBean factory =
            new LocalContainerEntityManagerFactoryBean();
    private @NonNull EntityManager database = mock();
    private @NonNull TransactionTemplate transactions = new TransactionTemplate();
    private @NonNull ScopedPluginStorage host = mock();
    private @NonNull PluginLifecycle plugin = mock();
    private @NonNull PluginStorage first = mock();
    private @NonNull PluginStorage second = mock();
    private @NonNull String session = UUID.randomUUID().toString();
    private static final PluginStorage.@NonNull Document VALUE =
            new PluginStorage.Document(1, new JsonValue.StringValue("payload"));

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        factory.setDataSource(source);
        factory.setPackagesToScan("top.focess.veto");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        var entityManagerFactory = Nullness.requireNonNull(factory.getObject());
        database = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        var manager = new JpaTransactionManager(entityManagerFactory);
        transactions = new TransactionTemplate(manager);
        host = new ScopedPluginStorage(database, manager, new ObjectMapper(), 1000);
        plugin = plugin("one");
        first = host.bind(plugin);
        second = host.bind(plugin("two"));
        transactions.executeWithoutResult(
                status -> {
                    database.persist(
                            new UserEntity(
                                    "owner", new byte[0], new byte[0], "USER", Instant.now()));
                    var row = new SessionEntity("owner", "test", "D:/workspace");
                    session = row.getId();
                    row.setPluginBindings(
                            List.of(
                                    new PluginBinding("one", "1.0.0", "1.0.0"),
                                    new PluginBinding("two", "1.0.0", "1.0.0")));
                    database.persist(row);
                });
    }

    private @NonNull PluginLifecycle plugin(@NonNull String id) {
        PluginLifecycle result = mock(PluginLifecycle.class);
        VetoPlugin implementation = mock(VetoPlugin.class);
        when(result.identity()).thenReturn(new PluginIdentity(id, "1.0.0"));
        when(result.implementation()).thenReturn(implementation);
        when(implementation.historicalIds()).thenReturn(Set.of());
        when(result.state()).thenReturn(PluginState.ACTIVE);
        return result;
    }

    @AfterEach
    void close() {
        factory.destroy();
    }

    private PluginStorage.@NonNull SessionScope scope(@NonNull PluginStorage storage) {
        var invocation = new PluginInvocationContext("owner", session);
        try {
            return storage.currentSession();
        } finally {
            invocation.close();
        }
    }

    private static <T> @NonNull T required(@Nullable T value) {
        if (value == null) throw new AssertionError("Transaction returned no result");
        return value;
    }

    @Test
    void retainedRecordsRemainVisibleAndExportableWithoutTheirPlugin() {
        UserContext.set("owner");
        try {
            var user = first.currentUser();
            first.user(user).put("retained-user", null, VALUE);
            first.session(scope(first)).put("retained-session", null, VALUE);
            first.application().put("retained-application", null, VALUE);

            var manager = mock(PluginManager.class);
            var data =
                    new RetainedPluginData(
                            database, manager, new RequestAuthorization("admin"::equals));
            var userPage =
                    required(
                            transactions.execute(
                                    status -> data.list(PluginScope.USER, null, null, 1)));
            assertEquals(1, userPage.entries().size());
            var userRecord = userPage.entries().getFirst();
            assertEquals("one", userRecord.pluginId());
            assertEquals(RetainedPluginData.Presence.ABSENT, userRecord.presence());
            assertEquals(RetainedPluginData.Interpretation.OPAQUE, userRecord.interpretation());
            assertFalse(
                    new ObjectMapper().valueToTree(userPage).path("entries").get(0).has("payload"));
            assertEquals(
                    "\"payload\"",
                    required(transactions.execute(status -> data.export(userRecord.id())))
                            .payload());
            assertEquals(
                    1,
                    required(
                                    transactions.execute(
                                            status ->
                                                    data.list(PluginScope.SESSION, null, null, 10)))
                            .entries()
                            .size());
            assertThrows(
                    ResponseStatusException.class,
                    () -> data.list(PluginScope.APPLICATION, null, null, 10));

            UserContext.set("intruder");
            assertTrue(
                    required(
                                    transactions.execute(
                                            status -> data.list(PluginScope.USER, null, null, 10)))
                            .entries()
                            .isEmpty());
            var denied =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> transactions.execute(status -> data.export(userRecord.id())));
            assertEquals(404, denied.getStatusCode().value());

            UserContext.set("admin");
            var application =
                    required(
                            transactions.execute(
                                    status -> data.list(PluginScope.APPLICATION, null, null, 10)));
            assertEquals(1, application.entries().size());
            assertEquals(
                    "\"payload\"",
                    required(
                                    transactions.execute(
                                            status ->
                                                    data.export(
                                                            application.entries().getFirst().id())))
                            .payload());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void preparationAndPresentationCannotMutateAnyStorageScope() {
        UserContext.set("owner");
        PluginStorage.UserScope user;
        try {
            user = first.currentUser();
        } finally {
            UserContext.clear();
        }
        var stores = List.of(first.application(), first.user(user), first.session(scope(first)));
        for (var store : stores) {
            var original = store.put("existing", null, VALUE);
            ToolCallContextHolder.withoutEffects(
                    () -> {
                        assertThrows(SecurityException.class, () -> store.put("new", null, VALUE));
                        assertThrows(
                                SecurityException.class,
                                () -> store.delete("existing", original.revision()));
                        ToolCallContextHolder.withoutEffects(
                                () -> {
                                    assertThrows(
                                            SecurityException.class,
                                            () -> store.put("nested", null, VALUE));
                                    return true;
                                });
                        assertThrows(
                                SecurityException.class,
                                () -> store.put("after-nested", null, VALUE));
                        return true;
                    });
            assertTrue(store.get("existing").isPresent());
            assertTrue(store.get("new").isEmpty());
            store.delete("existing", original.revision());
        }
    }

    @Test
    void authenticatedFrontendActionReceivesItsSessionStoreAndRestoresContext() throws Exception {
        var frontend =
                new FrontendContribution() {
                    public @NonNull String module() {
                        return "export default {}";
                    }

                    public @NonNull JsonValue handle(
                            @NonNull ActionContext scope,
                            @NonNull String action,
                            JsonValue.@NonNull ObjectValue arguments)
                            throws PluginFailure {
                        PluginStorage.Store store = first.session(first.currentSession());
                        if (action.equals("fail"))
                            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
                        store.put("frontend", null, VALUE);
                        return new JsonValue.StringValue("saved");
                    }
                };
        var catalog =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.FRONTEND, ignored -> {})
                        .stage(
                                new ContributionSource(
                                        "one", "1.0.0", ContributionSource.Origin.PLUGIN),
                                List.of(
                                        Contribution.of(
                                                StandardContributionPoints.FRONTEND,
                                                "panel",
                                                frontend)))
                        .freeze();
        SessionRepository sessions = mock(SessionRepository.class);
        SessionPlugins selected = mock(SessionPlugins.class);
        PluginManager plugins = mock(PluginManager.class);
        var row = database.find(SessionEntity.class, session);
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("test", "owner"))
                .thenReturn(Optional.of(Nullness.requireNonNull(row)));
        when(selected.bindings(session))
                .thenReturn(List.of(new PluginBinding("one", "1.0.0", "1.0.0")));
        when(plugins.catalog()).thenReturn(catalog);
        when(plugins.plugin("one")).thenReturn(plugin);
        when(plugin.execute(any()))
                .thenAnswer(
                        invocation -> {
                            PluginLifecycle.Operation<?> operation = invocation.getArgument(0);
                            if (operation == null) throw new AssertionError("Missing operation");
                            return operation.run();
                        });
        SessionAgentRegistry agents = mock(SessionAgentRegistry.class);
        when(agents.records(UUID.fromString(session)))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.AgentSummary(
                                        "agent",
                                        "Offline agent",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        true,
                                        null,
                                        null)));
        var controller =
                new PluginFrontendController(
                        new RequestAuthorization(user -> false),
                        sessions,
                        selected,
                        plugins,
                        agents);
        var outer = new PluginInvocationContext("owner", "outer");
        UserContext.set("owner");
        try {
            controller.act(
                    "test",
                    new PluginFrontendController.ActionRequest(
                            "one:panel", "agent", "save", new ObjectMapper().createObjectNode()));
            assertSame(outer, Nullness.requireNonNull(PluginInvocationContext.current()));
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            controller.act(
                                    "test",
                                    new PluginFrontendController.ActionRequest(
                                            "one:panel",
                                            "agent",
                                            "fail",
                                            new ObjectMapper().createObjectNode())));
            assertSame(outer, Nullness.requireNonNull(PluginInvocationContext.current()));
        } finally {
            outer.close();
            UserContext.clear();
        }
        assertThrows(SecurityException.class, first::currentSession);
        assertTrue(first.session(scope(first)).get("frontend").isPresent());
    }

    @Test
    void readsWithinTheOuterTransactionSeeCasUpdateAndRecreation() {
        var store = first.session(scope(first));
        var initial = store.put("transaction", null, VALUE);
        transactions.executeWithoutResult(
                status -> {
                    assertEquals(VALUE, store.get("transaction").orElseThrow().document());
                    var updated =
                            store.put(
                                    "transaction",
                                    initial.revision(),
                                    new PluginStorage.Document(
                                            2, new JsonValue.StringValue("updated")));
                    assertEquals(updated, store.get("transaction").orElseThrow());
                    store.delete("transaction", updated.revision());
                    assertTrue(store.get("transaction").isEmpty());
                    var recreated = store.put("transaction", null, VALUE);
                    assertEquals(recreated, store.get("transaction").orElseThrow());
                });
    }

    @Test
    void scopesAndNamespacesAreBoundAndUnforgeable() {
        var authorized = scope(first);
        var a = first.session(authorized);
        var b = second.session(scope(second));
        a.put("same", null, VALUE);
        first.application().put("same", null, VALUE);
        assertTrue(b.get("same").isEmpty());
        assertThrows(SecurityException.class, () -> second.session(authorized));
        assertThrows(
                SecurityException.class,
                () ->
                        first.session(
                                new PluginStorage.SessionScope(
                                        authorized.token(), "forged", session)));
        assertEquals(1, first.scopes(PluginScope.SESSION, null, 50).entries().size());
        assertTrue(a.list("same", null, 50).entries().size() == 1);
    }

    @Test
    void serviceScopeTransferIssuesProviderOwnedGrant() {
        var callerSession = scope(first);
        var providerSession = host.transferSession(first, callerSession, second);
        assertNotEquals(callerSession.token(), providerSession.token());
        assertEquals(callerSession.userId(), providerSession.userId());
        assertEquals(callerSession.sessionId(), providerSession.sessionId());
        assertDoesNotThrow(() -> second.session(providerSession));
        assertThrows(SecurityException.class, () -> first.session(providerSession));
        assertThrows(SecurityException.class, () -> second.session(callerSession));
        assertThrows(
                SecurityException.class,
                () ->
                        host.transferSession(
                                first,
                                new PluginStorage.SessionScope(
                                        "forged",
                                        callerSession.userId(),
                                        callerSession.sessionId()),
                                second));

        PluginStorage.UserScope callerUser;
        UserContext.set("owner");
        try {
            callerUser = first.currentUser();
        } finally {
            UserContext.clear();
        }
        var providerUser = host.transferUser(first, callerUser, second);
        assertNotEquals(callerUser.token(), providerUser.token());
        assertDoesNotThrow(() -> second.user(providerUser));
        assertThrows(SecurityException.class, () -> first.user(providerUser));
    }

    @Test
    void casAndDeletionRecreationRejectStaleRevisions() {
        var store = first.application();
        var initial = store.put("key", null, VALUE);
        var left = CompletableFuture.supplyAsync(() -> attempt(store, initial.revision()));
        var right = CompletableFuture.supplyAsync(() -> attempt(store, initial.revision()));
        assertNotEquals(left.join(), right.join());
        var current = store.get("key").orElseThrow();
        store.delete("key", current.revision());
        store.put("key", null, VALUE);
        assertThrows(
                PluginStorage.Conflict.class, () -> store.put("key", initial.revision(), VALUE));
        assertThrows(PluginStorage.Conflict.class, () -> store.delete("key", current.revision()));
    }

    private boolean attempt(PluginStorage.@NonNull Store store, String revision) {
        try {
            store.put("key", revision, VALUE);
            return true;
        } catch (PluginStorage.Conflict expected) {
            return false;
        }
    }

    @Test
    void concurrentInsertIfAbsentHasExactlyOneWinner() {
        var store = first.application();
        var left = CompletableFuture.supplyAsync(() -> attempt(store, null));
        var right = CompletableFuture.supplyAsync(() -> attempt(store, null));
        assertNotEquals(left.join(), right.join());
        assertEquals(VALUE, store.get("key").orElseThrow().document());
    }

    @Test
    void deletionRollbackAndCommitAreAtomicAndStaleHandlesCannotReviveSession() {
        var store = first.session(scope(first));
        store.put("key", null, VALUE);
        transactions.executeWithoutResult(
                status -> {
                    host.deleteSession(session);
                    database.remove(database.find(SessionEntity.class, session));
                    status.setRollbackOnly();
                });
        assertTrue(store.get("key").isPresent());
        when(plugin.state()).thenReturn(PluginState.CLOSED);
        transactions.executeWithoutResult(
                status -> {
                    host.deleteSession(session);
                    database.remove(database.find(SessionEntity.class, session));
                });
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        assertThrows(SecurityException.class, () -> store.put("late", null, VALUE));
        assertTrue(first.scopes(PluginScope.SESSION, null, 50).entries().isEmpty());
    }

    @Test
    void stoppingRetainsDataAndPagingAndSizeLimitsAreExplicit() {
        var store = first.application();
        store.put("a", null, VALUE);
        store.put("b", null, VALUE);
        var page = store.list("", null, 1);
        assertEquals("a", page.entries().getFirst().key());
        assertEquals("b", store.list("", page.cursor(), 1).entries().getFirst().key());
        assertThrows(
                IllegalArgumentException.class,
                () -> second.application().list("", page.cursor(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.put(
                                "long",
                                null,
                                new PluginStorage.Document(
                                        1, new JsonValue.StringValue("x".repeat(1001)))));
        when(plugin.state()).thenReturn(PluginState.CLOSED);
        assertThrows(IllegalStateException.class, () -> store.get("a"));
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        assertTrue(first.application().get("a").isPresent());
    }

    @Test
    void userDeletionInvalidatesOldAccountScopeAndPreservesApplicationData() {
        UserContext.set("owner");
        PluginStorage.UserScope oldScope;
        try {
            oldScope = first.currentUser();
        } finally {
            UserContext.clear();
        }
        var userStore = first.user(oldScope);
        userStore.put("key", null, VALUE);
        first.session(scope(first)).put("key", null, VALUE);
        first.application().put("key", null, VALUE);
        transactions.executeWithoutResult(
                status -> {
                    host.deleteUser("owner");
                    database.remove(database.find(SessionEntity.class, session));
                    database.remove(database.find(UserEntity.class, "owner"));
                    database.flush();
                    database.persist(
                            new UserEntity(
                                    "owner", new byte[0], new byte[0], "USER", Instant.now()));
                });
        assertThrows(SecurityException.class, () -> userStore.put("resurrect", null, VALUE));
        assertTrue(first.application().get("key").isPresent());
        UserContext.set("owner");
        try {
            var replacement = first.currentUser();
            assertNotEquals(oldScope.userId(), replacement.userId());
            assertTrue(first.user(replacement).get("key").isEmpty());
        } finally {
            UserContext.clear();
        }
    }
}
