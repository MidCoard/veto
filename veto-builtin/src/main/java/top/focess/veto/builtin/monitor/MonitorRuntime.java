package top.focess.veto.builtin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.group.GroupObservations;

/** All monitor resources are created, restored and released by the builtin plugin. */
public final class MonitorRuntime implements AutoCloseable {
    private final PluginHost host;
    private final StoredMonitorRepository repository;
    private final @NonNull MonitorService service;
    private final @NonNull Listener listener;
    private ScheduledExecutorService scheduler;

    /** Creates the runtime, discovering the group observation source from the context. */
    public MonitorRuntime(@NonNull PluginContext context) {
        this(context, context.service(GroupObservations.class).orElse(List::of));
    }

    /** Creates the runtime with an explicit group observation source. */
    public MonitorRuntime(@NonNull PluginContext context, @NonNull GroupObservations groups) {
        host = context.service(PluginHost.class).orElse(null);
        repository =
                context.service(PluginStorage.class).map(StoredMonitorRepository::new).orElse(null);
        service =
                new MonitorService(
                        repository == null
                                ? new MonitorRepository() {
                                    public @NonNull List<@NonNull MonitorEntity> findAll() {
                                        return List.of();
                                    }

                                    public @NonNull MonitorEntity save(
                                            @NonNull MonitorEntity value) {
                                        throw new IllegalStateException(
                                                "Plugin storage unavailable");
                                    }
                                }
                                : repository,
                        new ObjectMapper().findAndRegisterModules(),
                        groups,
                        host);
        listener =
                new Listener() {
                    @EventHandler
                    public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
                        service.onSessionDeleted(event);
                    }

                    @EventHandler
                    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
                        service.onAgentTerminated(event);
                    }
                };
    }

    private volatile boolean ready;

    /** Returns the monitor view of the host agent-work-source contract. */
    public @NonNull AgentInbox work() {
        return new AgentInbox() {
            public @NonNull List<@NonNull Observation> pending(@NonNull InboxContext scope) {
                return ready ? service.pending(scope) : List.of();
            }

            public void started(@NonNull InboxContext scope, @NonNull Observation value) {
                service.started(scope, value);
            }

            public void completed(
                    @NonNull InboxContext scope, @NonNull Observation value, boolean success) {
                service.completed(scope, value, success);
            }

            public void cancelled(@NonNull InboxContext scope, @NonNull Observation value) {
                service.cancelled(scope, value);
            }
        };
    }

    /** Returns the underlying monitor service. */
    public @NonNull MonitorService service() {
        return service;
    }

    /** Event aspect for monitor lifecycle notifications. */
    public @NonNull Listener listener() {
        return listener;
    }

    /** Returns scope-checked monitor operations for the monitor tools. */
    public @NonNull MonitorOperations operations() {
        return new MonitorOperations() {
            private PluginHost.@NonNull Invocation scope(@NonNull String tool) {
                if (host == null) throw new IllegalStateException("Plugin host unavailable");
                return host.invocation(tool);
            }

            public @NonNull Object create(@NonNull String purpose, @NonNull Instant due) {
                var scope = scope("create_monitor");
                return service.createTimer(
                        scope.owner(),
                        scope.sessionId(),
                        scope.agentId(),
                        purpose,
                        due,
                        scope.requestId());
            }

            public @NonNull Object inspect() {
                var scope = scope("inspect_monitor");
                return service.list(scope.owner(), scope.sessionId()).stream()
                        .filter(row -> row.agentId().equals(scope.agentId()))
                        .toList();
            }

            public @NonNull Object control(@NonNull String id, @NonNull String operation) {
                var scope = scope(operation + "_monitor");
                return service.control(
                        scope.owner(), scope.sessionId(), scope.agentId(), id, operation);
            }
        };
    }

    /** Blocks the current invocation while group work awaits delivery. */
    public void awaitGroup() {
        if (host == null) throw new IllegalStateException("Plugin host unavailable");
        host.await("create_task", service.awaitGroup(host.invocation("create_task")));
    }

    /** Deserializes every persisted monitor record; empty when storage is unavailable. */
    public @NonNull List<@NonNull MonitorRecord> storedRecords() {
        var source = repository;
        if (source == null) return List.of();
        var mapper = new ObjectMapper().findAndRegisterModules();
        return source.findAll().stream()
                .map(
                        row -> {
                            try {
                                return mapper.readValue(row.getPayload(), MonitorRecord.class);
                            } catch (IOException error) {
                                throw new IllegalStateException("Invalid stored monitor", error);
                            }
                        })
                .toList();
    }

    /** Restores persisted monitors and starts the tick scheduler; no-op when already started. */
    public synchronized void start() {
        if (repository == null || scheduler != null) return;
        service.restore();
        ready = true;
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("builtin-monitors").factory());
        scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        service.tick();
                    } catch (RuntimeException failure) {
                        LoggerFactory.getLogger("top.focess.veto.builtin.monitor.MonitorRuntime")
                                .warn("Monitor tick failed; retrying", failure);
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        ready = false;
        service.closeWaits();
        var active = scheduler;
        if (active == null) return;
        active.shutdown();
        try {
            if (!active.awaitTermination(5, TimeUnit.SECONDS)) active.shutdownNow();
        } catch (InterruptedException failure) {
            active.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }
}
