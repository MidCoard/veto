package top.focess.veto.builtin.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                    return new Invocation("owner", "session", "agent", "request", "test-call");
                }

                public void wake(
                        @NonNull String owner, @NonNull String session, @NonNull String agent) {
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
                                    "Plugin context is not bound to a lifecycle owner");
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
                                    "owner",
                                    "session",
                                    "agent",
                                    "Review",
                                    Instant.now().plusSeconds(30),
                                    "request")
                            .id();
        }
        try (var restarted = runtime()) {
            restarted.start();
            var restored = restarted.service().list("owner", "session").getFirst();
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
                    "owner", "closed", "offline", "Review", Instant.now().plusSeconds(60));
            service.createTimer("owner", "kept", "other", "Review", Instant.now().plusSeconds(60));
            service.onSessionDeleted(
                    new SessionDeletedEvent(new Scope.SessionScope("foreign", "kept")));
            service.onSessionDeleted(
                    new SessionDeletedEvent(new Scope.SessionScope("owner", "closed")));
            assertTrue(service.list("owner", "closed").isEmpty());
            assertEquals("ACTIVE", service.list("owner", "kept").getFirst().state());
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
                                    "owner",
                                    "session",
                                    "original-agent",
                                    "Review",
                                    Instant.now().plusSeconds(60));
            var frontend = new MonitorFrontend(runtime.service());
            var scope = new Scope.AgentScope("owner", "session", "different-agent");
            var args = new JsonValue.ObjectValue(Map.of("id", new JsonValue.StringValue(row.id())));
            for (String operation : new String[] {"pause", "resume", "cancel"}) {
                assertEquals(
                        new JsonValue.BooleanValue(true), frontend.handle(scope, operation, args));
            }
            assertEquals(
                    "CANCELLED", runtime.service().list("owner", "session").getFirst().state());
            assertThrows(PluginFailure.class, () -> frontend.handle(scope, "restart", args));
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new Scope.AgentScope("foreign", "session", "agent"),
                                    "pause",
                                    args));
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new Scope.AgentScope("owner", "foreign", "agent"),
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
                        "owner",
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
                                    new Scope.AgentScope("owner", "session", "agent"),
                                    "pause",
                                    new JsonValue.ObjectValue(
                                            Map.of("id", new JsonValue.StringValue("group")))));
        }
    }
}
