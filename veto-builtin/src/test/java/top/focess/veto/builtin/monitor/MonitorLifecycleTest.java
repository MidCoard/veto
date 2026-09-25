package top.focess.veto.builtin.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Exercises the real feature with only the veto-api scoped storage contract, without veto-core. */
@NullMarked
class MonitorLifecycleTest {
    private final PluginStorage storage = new MemoryPluginStorage();
    private final AtomicInteger wakeups = new AtomicInteger();
    private final PluginHost host =
            new PluginHost() {
                public @NonNull Invocation invocation(String tool) {
                    return new Invocation("owner", "session", "agent", "request", "test-call");
                }

                public void wake(String owner, String session, String agent) {
                    wakeups.incrementAndGet();
                }

                public void invalidate(String session, String resource) {}
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
                        Map.of(PluginHost.class, host, PluginStorage.class, storage)));
    }

    @Test
    void importingOldActivationDoesNotInterruptAnOnlineActivation() throws Exception {
        try (var runtime = runtime()) {
            runtime.start();
            var service = runtime.service();
            var online =
                    service.createTimer(
                            "owner", "session", "agent", "Online", Instant.now().plusSeconds(30));
            service.tickAt(Instant.now().plusSeconds(31));
            var event = service.pending("agent", "session").getFirst();
            service.acknowledge("agent", event);
            service.activationStarted("agent", event);
            var legacy =
                    new MonitorRecord(
                                    "legacy",
                                    "owner",
                                    "session",
                                    "agent",
                                    "TIME_ONCE",
                                    "Legacy",
                                    null,
                                    Instant.now().minusSeconds(30),
                                    "COMPLETED",
                                    Map.of(),
                                    List.of(),
                                    Instant.now(),
                                    List.of())
                            .withActivation("legacy:due", MonitorRecord.ActivationState.RUNNING);
            var mapper = new ObjectMapper().findAndRegisterModules();
            assertTrue(
                    runtime.importLegacy(
                            new MonitorEntity("legacy", mapper.writeValueAsString(legacy))));
            runtime.reloadImported();
            var records = service.list("owner", "session");
            var preserved =
                    records.stream()
                            .filter(record -> record.id().equals(online.id()))
                            .findFirst()
                            .orElseThrow();
            var imported =
                    records.stream()
                            .filter(record -> record.id().equals("legacy"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    MonitorRecord.ActivationState.RUNNING,
                    preserved
                            .activationStates()
                            .getOrDefault(
                                    event.id(),
                                    new MonitorRecord.Activation(
                                            MonitorRecord.ActivationState.FAILED, Instant.now()))
                            .state());
            assertEquals(
                    MonitorRecord.ActivationState.INTERRUPTED,
                    imported.activationStates()
                            .getOrDefault(
                                    "legacy:due",
                                    new MonitorRecord.Activation(
                                            MonitorRecord.ActivationState.FAILED, Instant.now()))
                            .state());
        }
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
            service.onSessionClosed("foreign", "kept");
            service.onSessionClosed("owner", "closed");
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
            var scope = new FrontendContribution.Scope("owner", "session", "different-agent");
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
                                    new FrontendContribution.Scope("foreign", "session", "agent"),
                                    "pause",
                                    args));
            assertThrows(
                    PluginFailure.class,
                    () ->
                            frontend.handle(
                                    new FrontendContribution.Scope("owner", "foreign", "agent"),
                                    "pause",
                                    args));
            assertTrue(frontend.contribution().module().contains("registerInspector"));
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
                                    new FrontendContribution.Scope("owner", "session", "agent"),
                                    "pause",
                                    new JsonValue.ObjectValue(
                                            Map.of("id", new JsonValue.StringValue("group")))));
        }
    }

    @Test
    void largeLegacyPayloadIsImportedWithoutChangingIdentityOrOverwritingNewState()
            throws Exception {
        var repository = new StoredMonitorRepository(storage);
        String payload =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "id",
                                        "legacy",
                                        "owner",
                                        "owner",
                                        "sessionId",
                                        "session",
                                        "requestId",
                                        "original-request",
                                        "continuationId",
                                        "monitor:event-123",
                                        "content",
                                        "x".repeat(150000)));
        assertTrue(repository.importLegacy(new MonitorEntity("legacy", payload)));
        assertFalse(repository.importLegacy(new MonitorEntity("legacy", payload)));
        var restarted = new StoredMonitorRepository(storage);
        assertEquals(payload, restarted.findAll().getFirst().getPayload());
        String newer = payload.replace("original-request", "newer-request");
        restarted.save(new MonitorEntity("legacy", newer));
        assertFalse(repository.importLegacy(new MonitorEntity("legacy", payload)));
        assertEquals(newer, new StoredMonitorRepository(storage).findAll().getFirst().getPayload());
        assertThrows(
                IllegalStateException.class,
                () -> repository.importLegacy(new MonitorEntity("broken", "{")));
    }
}
