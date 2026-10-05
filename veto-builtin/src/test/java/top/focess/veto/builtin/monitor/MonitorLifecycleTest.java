package top.focess.veto.builtin.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

                public void invalidate(@NonNull String session, @NonNull String resource) {}
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
