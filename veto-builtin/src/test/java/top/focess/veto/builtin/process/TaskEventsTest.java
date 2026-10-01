package top.focess.veto.builtin.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorRepository;
import top.focess.veto.builtin.monitor.MonitorService;

class TaskEventsTest {
    @Test
    void logoutSuppressesNotificationsUntilTheUserAuthenticatesAgain() {
        List<String> observed = new ArrayList<>();
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull String owner,
                            @NonNull String session,
                            @NonNull String agent) {}

                    public void invalidate(@NonNull String session, @NonNull String resource) {}
                };
        var events =
                new TaskEvents(
                        host, (owner, task, cause) -> observed.add(owner + ":" + task.taskId()));
        var session = UUID.randomUUID();
        var scope = new Scope.AgentScope("owner", session.toString(), "agent");
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
        assertEquals(List.of("owner:bg-1"), observed);
        events.close();
    }

    @Test
    void retriesExitWithCapturedOwnerAndStopsRetryingAfterClose() {
        var fail = new AtomicBoolean(true);
        List<String> observations = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        var session = UUID.randomUUID();
        var scope = new Scope.AgentScope("spawn-owner", session.toString(), "agent");
        PluginHost host =
                new PluginHost() {
                    public @NonNull Invocation invocation(@NonNull String tool) {
                        throw new UnsupportedOperationException();
                    }

                    public void wake(
                            @NonNull String owner,
                            @NonNull String session,
                            @NonNull String agent) {}

                    public void invalidate(@NonNull String session, @NonNull String resource) {
                        throw new AssertionError("Expected complete scoped invalidation identity");
                    }

                    @Override
                    public void invalidate(
                            Scope.@NonNull SessionScope target, @NonNull String resource) {
                        assertEquals(scope.sessionScope(), target);
                        assertEquals("tasks", resource);
                    }

                    @Override
                    public void publish(
                            Scope.@NonNull SessionScope target,
                            @NonNull String topic,
                            JsonValue.@NonNull ObjectValue facts) {
                        assertEquals(scope.sessionScope(), target);
                        assertEquals(
                                new JsonValue.StringValue(scope.agent()),
                                facts.values().get("agentId"));
                        topics.add(topic);
                    }
                };
        var events =
                new TaskEvents(
                        host,
                        (owner, task, cause) -> {
                            if (fail.get()) throw new IllegalStateException("store unavailable");
                            observations.add(owner + ":" + task.alive() + ":" + cause);
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
        assertEquals(List.of("spawn-owner:false:AUTO_KILL"), observations);
        assertEquals(List.of("task_started", "task_exited", "task_started"), topics);
        fail.set(true);
        events.changed(
                scope, exited, BackgroundTasks.ExitCause.AUTO_KILL, BackgroundTasks.Change.EXITED);
        events.close();
        fail.set(false);
        events.retry();
        events.changed(
                scope, live, BackgroundTasks.ExitCause.NATURAL, BackgroundTasks.Change.STARTED);
        assertEquals(1, observations.size());
        assertEquals(4, topics.size());
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
                            @NonNull String owner,
                            @NonNull String session,
                            @NonNull String agent) {}

                    public void invalidate(@NonNull String session, @NonNull String resource) {}
                };
        var events =
                new TaskEvents(
                        host,
                        (owner, task, cause) -> {
                            if (!available.get()) throw new IllegalStateException();
                            received.add(task);
                        });
        var session = UUID.randomUUID();
        var scope = new Scope.AgentScope("owner", session.toString(), "agent");
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
        events.sessionDeleted(new Scope.SessionScope("owner", session.toString()));
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
                            @NonNull String owner,
                            @NonNull String session,
                            @NonNull String agent) {}

                    public void invalidate(@NonNull String session, @NonNull String resource) {}
                };
        var events = new TaskEvents(host, monitor);
        var session = UUID.randomUUID();
        var instance = UUID.randomUUID();
        var scope = new Scope.AgentScope("owner", session.toString(), "agent");
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
        var saved = monitor.list("owner", session.toString()).getFirst();
        var replayed = mapper.readValue(mapper.writeValueAsString(saved), MonitorRecord.class);
        assertEquals(event, replayed.pending().getFirst());
        events.close();
    }
}
