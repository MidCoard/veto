package top.focess.veto.builtin.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorRepository;
import top.focess.veto.builtin.monitor.MonitorService;

class TaskEventsTest {
    @Test
    void removalBeforeExitRetainsFinalObservationAndSuppressesLateCallbacks() {
        var host = mock(PluginHost.class);
        var available = new AtomicBoolean(false);
        var observed = new ArrayList<TaskInfo>();
        var scope = new Scope.AgentScope(UUID.randomUUID(), UUID.randomUUID().toString(), "agent");
        var events =
                new TaskEvents(
                        host,
                        (owner, task, cause) -> {
                            if (!available.get())
                                throw new IllegalStateException("Store unavailable");
                            observed.add(task);
                        });
        var instance = UUID.randomUUID();
        var now = Instant.now();
        var live =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        now,
                        true,
                        null,
                        1,
                        null,
                        UUID.fromString(scope.session()),
                        instance);
        var exited =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        now,
                        false,
                        0,
                        1,
                        now.plusSeconds(1),
                        UUID.fromString(scope.session()),
                        instance);
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.USER_STOP, BackgroundTasks.Change.REMOVED);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.USER_STOP, BackgroundTasks.Change.EXITED);
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        available.set(true);
        events.retry();
        events.retry();
        assertEquals(List.of(exited), observed);
        verify(host).publish(eq(scope.sessionScope()), eq("task_started"), any());
        verify(host).publish(eq(scope.sessionScope()), eq("task_removed"), any());
        verify(host, never()).publish(eq(scope.sessionScope()), eq("task_exited"), any());
        events.close();
    }

    @Test
    void lifecyclePreviewBoundsMetadataWithoutSplittingUnicode() {
        var task =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "x".repeat(1023) + "😀tail",
                        "y".repeat(1200),
                        Instant.now(),
                        true,
                        null,
                        7,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        var row = TasksFrontend.row(task);
        assertEquals("x".repeat(1023), row.get("command"));
        assertEquals("y".repeat(1024), row.get("cwd"));
        assertEquals(true, row.get("commandTruncated"));
        assertEquals(true, row.get("cwdTruncated"));
        assertEquals(task.taskInstanceId().toString(), row.get("taskInstanceId"));
        assertFalse(row.containsKey("recentOutput"));
    }

    @Test
    void logoutSuppressesNotificationsUntilTheUserAuthenticatesAgain() {
        List<String> observed = new ArrayList<>();
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}
                };
        var events =
                new TaskEvents(
                        host, (userId, task, cause) -> observed.add(userId + ":" + task.taskId()));
        var session = UUID.randomUUID();
        var scope =
                new Scope.AgentScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        session.toString(),
                        "agent");
        var task =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        Instant.now(),
                        false,
                        0,
                        1,
                        Instant.now(),
                        session,
                        UUID.randomUUID(),
                        "request");

        events.userLogout(scope.userScope());
        events.changed(
                scope, task, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.EXITED);
        assertTrue(observed.isEmpty());

        events.userAuthenticated(scope.userScope());
        events.changed(
                scope, task, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.EXITED);
        assertEquals(List.of(scope.userId() + ":bg-1"), observed);
        events.close();
    }

    @Test
    void retriesExitWithCapturedOwnerAndStopsRetryingAfterClose() {
        var fail = new AtomicBoolean(true);
        List<String> observations = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        var session = UUID.randomUUID();
        var scope =
                new Scope.AgentScope(
                        UUID.fromString("4de2fe72-0a34-5627-8e39-476676c86bb9"),
                        session.toString(),
                        "agent");
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}

                    @Override
                    public void publish(
                            Scope.@NonNull SessionScope target,
                            @NonNull String topic,
                            JsonValue.@NonNull ObjectValue facts) {
                        assertEquals(scope.sessionScope(), target);
                        assertEquals(
                                new JsonValue.StringValue(scope.agent()),
                                facts.values().get("agentId"));
                        var payload = JsonValues.toMap(facts);
                        if (!(payload.get("task") instanceof Map<?, ?> task))
                            throw new AssertionError("Expected task preview");
                        assertEquals("bg-1", task.get("taskId"));
                        assertEquals("program", task.get("command"));
                        assertEquals("workspace", task.get("cwd"));
                        assertEquals(payload.get("taskInstanceId"), task.get("taskInstanceId"));
                        assertEquals(payload.get("alive"), task.get("alive"));
                        assertFalse(task.containsKey("recentOutput"));
                        topics.add(topic);
                    }
                };
        var events =
                new TaskEvents(
                        host,
                        (userId, task, cause) -> {
                            if (fail.get()) throw new IllegalStateException("store unavailable");
                            observations.add(userId + ":" + task.alive() + ":" + cause);
                        });
        var instance = UUID.randomUUID();
        var started = Instant.now();
        var live =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        started,
                        true,
                        null,
                        1,
                        null,
                        session,
                        instance);
        var exited =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        started,
                        false,
                        0,
                        1,
                        Instant.now(),
                        session,
                        instance);
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.AUTO_KILL, BackgroundTasks.Change.EXITED);
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        fail.set(false);
        events.retry();
        events.retry();
        assertEquals(List.of(scope.userId() + ":false:AUTO_KILL"), observations);
        assertEquals(List.of("task_started", "task_exited"), topics);
        fail.set(true);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.AUTO_KILL, BackgroundTasks.Change.EXITED);
        events.close();
        fail.set(false);
        events.retry();
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        assertEquals(1, observations.size());
        assertEquals(2, topics.size());
    }

    @Test
    void closingSessionCancelsPendingObservationBeforeExitCanRecreateIt() {
        var available = new AtomicBoolean(false);
        var received = new ArrayList<TaskInfo>();
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}
                };
        var events =
                new TaskEvents(
                        host,
                        (userId, task, cause) -> {
                            if (!available.get()) throw new IllegalStateException();
                            received.add(task);
                        });
        var session = UUID.randomUUID();
        var scope =
                new Scope.AgentScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        session.toString(),
                        "agent");
        var exited =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        Instant.now(),
                        false,
                        0,
                        1,
                        Instant.now(),
                        session,
                        UUID.randomUUID(),
                        "original-request");
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.EXITED);
        events.sessionDeleted(
                new Scope.SessionScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        session.toString()));
        available.set(true);
        events.retry();
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.SHUTDOWN, BackgroundTasks.Change.EXITED);
        assertTrue(received.isEmpty());
        events.close();
    }

    @Test
    void persistedMonitorExitKeepsOriginalRequestAndInstanceAcrossRetry() throws Exception {
        var repository = mock(MonitorRepository.class);
        var available = new AtomicBoolean(false);
        when(repository.save(any()))
                .thenAnswer(
                        call -> {
                            if (!available.get()) throw new IllegalStateException();
                            return call.getArgument(0);
                        });
        var mapper = new ObjectMapper().findAndRegisterModules();
        var monitor = new MonitorService(repository, mapper, () -> List.of(), null);
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}
                };
        var events = new TaskEvents(host, monitor);
        var session = UUID.randomUUID();
        var instance = UUID.randomUUID();
        var scope =
                new Scope.AgentScope(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        session.toString(),
                        "agent");
        var now = Instant.now();
        var live =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        now,
                        true,
                        null,
                        1,
                        null,
                        session,
                        instance,
                        "original-request");
        var exited =
                new TaskInfo(
                        "bg-1",
                        "agent",
                        "program",
                        "workspace",
                        now,
                        false,
                        0,
                        1,
                        now.plusSeconds(1),
                        session,
                        instance,
                        "original-request");
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.USER_STOP, BackgroundTasks.Change.EXITED);
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        assertTrue(monitor.pending("agent", session.toString()).isEmpty());
        available.set(true);
        events.retry();
        var event = monitor.pending("agent", session.toString()).getFirst();
        assertEquals("original-request", event.requestId());
        assertEquals(instance.toString(), event.dispatchId());
        assertTrue(event.content().contains("USER_STOP"));
        events.retry();
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.USER_STOP, BackgroundTasks.Change.EXITED);
        assertEquals(1, monitor.pending("agent", session.toString()).size());
        var saved =
                monitor.list(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                session.toString())
                        .getFirst();
        var replayed = mapper.readValue(mapper.writeValueAsString(saved), MonitorRecord.class);
        assertEquals(event, replayed.pending().getFirst());
        events.close();
    }
}
