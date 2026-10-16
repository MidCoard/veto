package top.focess.veto.builtin.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Exercises the real feature with only the veto-api scoped storage contract, without veto-core. */
class MonitorLifecycleTest {
    private final @NonNull PluginStorage storage = new MemoryPluginStorage();
    private final @NonNull AtomicInteger wakeups = new AtomicInteger();

    private record Publication(
            @NonNull String session, @NonNull String topic, JsonValue.@NonNull ObjectValue data) {}

    private final @NonNull List<Publication> publications = new ArrayList<>();
    private final @NonNull PluginHost host =
            new PluginHost() {
                public @NonNull Invocation invocation(@NonNull String tool) {
                    return new Invocation(
                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                            "session",
                            "agent",
                            "request",
                            "test-call");
                }

                public void wake(
                        @NonNull UUID userId, @NonNull String session, @NonNull String agent) {
                    wakeups.incrementAndGet();
                }

                public void publish(
                        @NonNull String session,
                        @NonNull String topic,
                        JsonValue.@NonNull ObjectValue data) {
                    publications.add(new Publication(session, topic, data));
                }
            };

    private @NonNull MonitorRuntime runtime() {
        return new MonitorRuntime(
                new PluginContext(
                        new PluginIdentity("top.focess.builtin", "1.0.100"),
                        () -> {},
                        () -> {
                            throw new IllegalStateException(
                                    "Plugin context is not bound to a lifecycle userId");
                        },
                        Map.of(PluginHost.class, host, PluginStorage.class, storage),
                        Map.of()));
    }

    @Test
    void ownsPayloadRestorationAndShutdownWithoutCore() throws Exception {
        String id;
        try (var runtime = runtime()) {
            runtime.start();
            id =
                    runtime.service()
                            .createTimer(
                                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                    "session",
                                    "agent",
                                    "Review",
                                    Instant.now().plusSeconds(30),
                                    "request")
                            .id();
        }
        try (var restarted = runtime()) {
            restarted.start();
            var restored =
                    restarted
                            .service()
                            .list(
                                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                    "session")
                            .getFirst();
            assertEquals(id, restored.id());
            assertEquals("ACTIVE", restored.state());
            assertEquals("request", restored.requestId());
            restarted.service().tickAt(Instant.now().plusSeconds(31));
            assertEquals(1, wakeups.get());
        }
        int stopped = wakeups.get();
        Thread.sleep(1100);
        assertEquals(stopped, wakeups.get(), "Plugin shutdown must stop scheduled wakeups");
    }

    @Test
    void sessionDeletionReleasesOfflineRemindersWithoutWritingDeletedScope() throws Exception {
        try (var runtime = runtime()) {
            runtime.start();
            var service = runtime.service();
            service.createTimer(
                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                    "closed",
                    "offline",
                    "Review",
                    Instant.now().plusSeconds(60));
            service.createTimer(
                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                    "kept",
                    "other",
                    "Review",
                    Instant.now().plusSeconds(60));
            service.onSessionDeleted(
                    new SessionDeletedEvent(
                            new Scope.SessionScope(
                                    UUID.fromString("ec629ca2-6e80-51d3-a243-d00a0c2fcb52"),
                                    "kept")));
            service.onSessionDeleted(
                    new SessionDeletedEvent(
                            new Scope.SessionScope(
                                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                    "closed")));
            assertTrue(
                    service.list(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "closed")
                            .isEmpty());
            assertEquals(
                    "ACTIVE",
                    service.list(UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "kept")
                            .getFirst()
                            .state());
        }
    }

    @Test
    void frontendControlsStoredRecipientAndRejectsForeignScopeAndUnknownOperations()
            throws Exception {
        try (var runtime = runtime()) {
            runtime.start();
            var row =
                    runtime.service()
                            .createTimer(
                                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                    "session",
                                    "original-agent",
                                    "Review",
                                    Instant.now().plusSeconds(60));
            var frontend = new MonitorFrontend(runtime.service());
            var scope =
                    new Scope.AgentScope(
                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                            "session",
                            "different-agent");
            var args = new JsonValue.ObjectValue(Map.of("id", new JsonValue.StringValue(row.id())));
            for (String operation : new String[] {"pause", "resume", "cancel"}) {
                assertEquals(
                        new JsonValue.BooleanValue(true), frontend.handle(scope, operation, args));
            }
            assertEquals(
                    "CANCELLED",
                    runtime.service()
                            .list(
                                    UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                    "session")
                            .getFirst()
                            .state());
            assertThrows(PluginFailure.class, () -> frontend.handle(scope, "restart", args));
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new Scope.AgentScope(
                                            UUID.fromString("ec629ca2-6e80-51d3-a243-d00a0c2fcb52"),
                                            "session",
                                            "agent"),
                                    "pause",
                                    args));
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new Scope.AgentScope(
                                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                            "foreign",
                                            "agent"),
                                    "pause",
                                    args));
            assertTrue(frontend.module().contains("registerInspector"));
        }
    }

    @Test
    void publishesBoundedRowsWithTheSameRevisionAsTheControllerSnapshot() throws Exception {
        try (var runtime = runtime()) {
            var service = runtime.service();
            var user = UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376");
            var row =
                    service.createTimer(
                            user,
                            "session",
                            "agent",
                            "Review".repeat(500),
                            Instant.now().plusSeconds(60));
            var frontend = new MonitorFrontend(service);
            var scope = new Scope.AgentScope(user, "session", "agent");
            var list =
                    (JsonValue.ObjectValue)
                            frontend.handle(scope, "list", new JsonValue.ObjectValue(Map.of()));
            var publication = publications.getLast();
            assertEquals("session", publication.session());
            assertEquals("monitor_changed", publication.topic());
            assertEquals(
                    list.values().get("revision"), publication.data().values().get("revision"));
            if (!(list.values().get("items") instanceof JsonValue.ArrayValue items))
                throw new AssertionError("Monitor list must contain an items array");
            assertEquals(items.values().getFirst(), publication.data().values().get("item"));
            if (!(publication.data().values().get("item") instanceof JsonValue.ObjectValue item))
                throw new AssertionError("Monitor action must contain its item");
            if (!(item.values().get("purpose") instanceof JsonValue.StringValue purpose))
                throw new AssertionError("Monitor item must contain purpose text");
            assertEquals(2000, purpose.value().length());
            assertEquals(new JsonValue.BooleanValue(true), item.values().get("purposeTruncated"));
            assertFalse(item.values().containsKey("activationStates"));
            assertFalse(item.values().containsKey("pendingEvents"));
            var before = publication.data().values().get("revision");
            service.control(user, "session", "agent", row.id(), "pause");
            var changed = publications.getLast().data();
            assertNotEquals(before, changed.values().get("revision"));
            if (!(changed.values().get("item") instanceof JsonValue.ObjectValue updated))
                throw new AssertionError("Monitor control must publish its updated item");
            assertEquals(new JsonValue.StringValue("PAUSED"), updated.values().get("state"));
            int count = publications.size();
            service.control(user, "session", "agent", row.id(), "pause");
            assertEquals(
                    count, publications.size(), "Unchanged records produce no duplicate action");
        }
    }

    @Test
    void failedPersistenceDoesNotPublishOrAdvanceTheSnapshot() {
        var repository =
                new MonitorRepository() {
                    public @NonNull List<MonitorEntity> findAll() {
                        return List.of();
                    }

                    public @NonNull MonitorEntity save(@NonNull MonitorEntity entity) {
                        throw new IllegalStateException("Storage unavailable");
                    }
                };
        var service =
                new MonitorService(
                        repository,
                        new ObjectMapper().findAndRegisterModules(),
                        () -> List.of(),
                        host);
        var user = UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376");
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.createTimer(
                                user, "session", "agent", "Review", Instant.now().plusSeconds(60)));
        assertTrue(publications.isEmpty());
        assertEquals(0, service.snapshot(user, "session").revision());
        assertTrue(service.snapshot(user, "session").records().isEmpty());
    }

    @Test
    void frontendDoesNotControlGroupManagedSubscriptions() throws Exception {
        var repository = new StoredMonitorRepository(storage);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var record =
                new MonitorRecord(
                        "group",
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "agent",
                        "RESOURCE_EVENT",
                        "Group",
                        "id",
                        null,
                        "ACTIVE",
                        Map.of(),
                        List.of(),
                        Instant.now());
        repository.save(new MonitorEntity("group", mapper.writeValueAsString(record)));
        try (var runtime = runtime()) {
            runtime.start();
            var frontend = new MonitorFrontend(runtime.service());
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new Scope.AgentScope(
                                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                            "session",
                                            "agent"),
                                    "pause",
                                    new JsonValue.ObjectValue(
                                            Map.of("id", new JsonValue.StringValue("group")))));
        }
    }
}
