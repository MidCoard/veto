package top.focess.veto.builtin.process;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.process.BackgroundTasks.Change;
import top.focess.veto.builtin.process.BackgroundTasks.ExitCause;
import top.focess.veto.builtin.process.BackgroundTasks.Scope;

/** Builtin owns process-to-monitor interpretation, retries and frontend invalidation. */
public final class TaskEvents implements BackgroundTasks.Listener, AutoCloseable {
    private record Pending(
            @NonNull Scope scope, @NonNull TaskInfo task, @NonNull ExitCause cause) {}

    private final @NonNull PluginHost host;
    private final @NonNull ProcessObserver observer;
    private final @NonNull ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean closed;
    private final @NonNull Set<String> closedOwners = new HashSet<>();
    private final @NonNull Set<String> closedSessions = new HashSet<>();
    private final @NonNull Map<UUID, Scope> instances = new LinkedHashMap<>();
    private final @NonNull Set<UUID> closedInstances = new HashSet<>();

    public synchronized void ownerClosed(@NonNull String owner) {
        closedOwners.add(owner);
        pending.values().removeIf(value -> value.scope().owner().equals(owner));
    }

    public synchronized void sessionClosed(@NonNull String owner, @NonNull String session) {
        closedSessions.add(owner + ":" + session);
        pending.values()
                .removeIf(
                        value ->
                                value.scope().owner().equals(owner)
                                        && value.scope().session().equals(session));
    }

    public synchronized void agentClosed(@NonNull Scope scope) {
        instances.forEach(
                (id, owned) -> {
                    if (owned.equals(scope)) closedInstances.add(id);
                });
        pending.values().removeIf(value -> value.scope().equals(scope));
    }

    private boolean revoked(@NonNull Scope scope) {
        return closedOwners.contains(scope.owner())
                || closedSessions.contains(scope.owner() + ":" + scope.session());
    }

    public TaskEvents(@NonNull PluginHost host, @NonNull ProcessObserver observer) {
        this.host = host;
        this.observer = observer;
    }

    public synchronized void start() {
        if (closed || scheduler != null) return;
        var timer =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("builtin-process-events").factory());
        scheduler = timer;
        timer.scheduleWithFixedDelay(this::retry, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void changed(
            @NonNull Scope scope,
            @NonNull TaskInfo info,
            @NonNull ExitCause cause,
            @NonNull Change change) {
        if (closed || revoked(scope) || closedInstances.contains(info.taskInstanceId())) return;
        instances.put(info.taskInstanceId(), scope);
        if (change != Change.REMOVED) {
            pending.compute(
                    info.taskInstanceId(),
                    (key, prior) ->
                            prior != null && !prior.task().alive() && info.alive()
                                    ? prior
                                    : new Pending(scope, info, cause));
            flush(info.taskInstanceId());
        }
        try {
            Map<@NonNull String, @NonNull JsonValue> facts = new LinkedHashMap<>();
            facts.put("agentId", new JsonValue.StringValue(scope.agent()));
            facts.put("taskId", new JsonValue.StringValue(info.taskId()));
            facts.put(
                    "taskInstanceId", new JsonValue.StringValue(info.taskInstanceId().toString()));
            facts.put("alive", new JsonValue.BooleanValue(info.alive()));
            facts.put("cause", new JsonValue.StringValue(cause.name()));
            host.publish(
                    scope.session(),
                    "task_" + change.name().toLowerCase(Locale.ROOT),
                    new JsonValue.ObjectValue(facts));
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(ToolDocs.nonNullClass(TaskEvents.class))
                    .debug(
                            "Process event transport unavailable: {}",
                            error.getClass().getSimpleName());
        }
        try {
            host.invalidate(scope.session(), "tasks");
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(ToolDocs.nonNullClass(TaskEvents.class))
                    .debug(
                            "Process invalidation unavailable: {}",
                            error.getClass().getSimpleName());
        }
    }

    synchronized void retry() {
        if (closed) return;
        for (UUID id : List.copyOf(pending.keySet())) flush(id);
    }

    private void flush(@NonNull UUID id) {
        Pending value = pending.get(id);
        if (value == null) return;
        try {
            observer.changed(value.scope().owner(), value.task(), value.cause().name());
            pending.remove(id, value);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(ToolDocs.nonNullClass(TaskEvents.class))
                    .debug(
                            "Process observation awaits persistence retry: {}",
                            failure.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        var timer = scheduler;
        if (timer != null) timer.shutdownNow();
        pending.clear();
    }
}
