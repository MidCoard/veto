package top.focess.veto.builtin.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.builtin.process.BackgroundTasks.Change;
import top.focess.veto.builtin.process.BackgroundTasks.ExitCause;

/**
 * Builtin owns process-to-monitor interpretation, retries and frontend task publications.
 *
 * <p>Process workers, lifecycle events and the retry scheduler share this object's monitor. It
 * serializes revocation markers, instance tracking and pending observation delivery, including
 * observer and host callbacks. PendingTaskObservation observations use an ordinary map guarded by
 * that monitor. Callbacks must not wait for task workers whose notifications need this monitor;
 * retry shutdown does not join a worker while holding it.
 */
public final class TaskEvents implements BackgroundTasks.TaskObserver, AutoCloseable {

    private final @NonNull PluginHost host;
    private final @NonNull ProcessObserver observer;
    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private final @NonNull Map<UUID, PendingTaskObservation> pending = new HashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean closed;
    private final @NonNull Set<Scope.UserScope> loggedOutUsers = new HashSet<>();
    private final @NonNull Set<Scope.SessionScope> deletedSessions = new HashSet<>();
    private final @NonNull Map<UUID, TaskInstanceState> instances = new LinkedHashMap<>();

    /** Allows new task notifications after a successful signup or login. */
    public synchronized void userAuthenticated(Scope.@NonNull UserScope scope) {
        loggedOutUsers.remove(scope);
    }

    /** Drops pending notifications when a user logs out. */
    public synchronized void userLogout(Scope.@NonNull UserScope scope) {
        loggedOutUsers.add(scope);
        pending.values().removeIf(value -> value.scope().userScope().equals(scope));
    }

    /** Drops pending notifications for a deleted session. */
    public synchronized void sessionDeleted(Scope.@NonNull SessionScope scope) {
        deletedSessions.add(scope);
        pending.values().removeIf(value -> value.scope().sessionScope().equals(scope));
    }

    /** Marks the agent's instances closed and drops their pending notifications. */
    public synchronized void agentTerminated(Scope.@NonNull AgentScope scope) {
        instances.replaceAll(
                (id, instance) ->
                        instance.scope().equals(scope)
                                ? new TaskInstanceState(scope, Change.REMOVED)
                                : instance);
        pending.values().removeIf(value -> value.scope().equals(scope));
    }

    private boolean revoked(Scope.@NonNull AgentScope scope) {
        return loggedOutUsers.contains(scope.userScope())
                || deletedSessions.contains(scope.sessionScope());
    }

    /** Creates the notifier over the given host and process observer. */
    public TaskEvents(@NonNull PluginHost host, @NonNull ProcessObserver observer) {
        this.host = host;
        this.observer = observer;
    }

    /** Starts the retry scheduler; no-op when closed or already started. */
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
            Scope.@NonNull AgentScope scope,
            @NonNull TaskInfo info,
            @NonNull ExitCause cause,
            @NonNull Change change) {
        if (closed || revoked(scope)) return;
        var previous = instances.get(info.taskInstanceId());
        if (previous != null
                && (previous.change() == change
                        || previous.change() == Change.REMOVED
                        || previous.change() == Change.EXITED && change == Change.STARTED)) return;
        instances.put(info.taskInstanceId(), new TaskInstanceState(scope, change));
        // Removal can overtake the exit callback after the process marks itself stopped.
        // Preserve that final observation before suppressing later callbacks for this instance.
        if (change != Change.REMOVED || previous == null || previous.change() == Change.STARTED) {
            pending.compute(
                    info.taskInstanceId(),
                    (key, prior) ->
                            prior != null && !prior.task().alive() && info.alive()
                                    ? prior
                                    : new PendingTaskObservation(scope, info, cause));
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
            facts.put("task", JsonValues.from(mapper.valueToTree(TasksFrontend.row(info))));
            host.publish(
                    scope.sessionScope(),
                    "task_" + change.name().toLowerCase(Locale.ROOT),
                    new JsonValue.ObjectValue(facts));
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(TaskEvents.class)
                    .debug(
                            "Process event transport unavailable: {}",
                            error.getClass().getSimpleName());
        }
    }

    synchronized void retry() {
        if (closed) return;
        for (UUID id : List.copyOf(pending.keySet())) flush(id);
    }

    private void flush(@NonNull UUID id) {
        PendingTaskObservation value = pending.get(id);
        if (value == null) return;
        try {
            observer.changed(value.scope().userId(), value.task(), value.cause().name());
            pending.remove(id, value);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(TaskEvents.class)
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

    /** Completed process observation awaiting delivery under the TaskEvents monitor. */
    private record PendingTaskObservation(
            Scope.@NonNull AgentScope scope, @NonNull TaskInfo task, @NonNull ExitCause cause) {}

    /** Last observed change for one exact process instance; owned by TaskEvents. */
    private record TaskInstanceState(Scope.@NonNull AgentScope scope, @NonNull Change change) {}
}
