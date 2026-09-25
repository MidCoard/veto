package top.focess.veto.builtin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.group.GroupObservations;

/** All monitor resources are created, restored and released by the builtin plugin. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class MonitorRuntime implements AutoCloseable {
    private final @Nullable PluginHost host;
    private final @Nullable StoredMonitorRepository repository;
    private final MonitorService service;
    private @Nullable ScheduledExecutorService scheduler;
    private final List<MonitorEntity> imported = new ArrayList<>();

    public MonitorRuntime(PluginContext context) {
        this(
                context,
                context.service(ToolDocs.nonNullClass(GroupObservations.class)).orElse(List::of));
    }

    public MonitorRuntime(PluginContext context, GroupObservations groups) {
        host = context.service(PluginHost.class).orElse(null);
        repository =
                context.service(ToolDocs.nonNullClass(PluginStorage.class))
                        .map(StoredMonitorRepository::new)
                        .orElse(null);
        service =
                new MonitorService(
                        repository == null
                                ? new MonitorRepository() {
                                    public List<MonitorEntity> findAll() {
                                        return List.of();
                                    }

                                    public MonitorEntity save(MonitorEntity value) {
                                        throw new IllegalStateException(
                                                "Plugin storage unavailable");
                                    }
                                }
                                : repository,
                        new ObjectMapper().findAndRegisterModules(),
                        groups,
                        host);
    }

    public synchronized boolean importLegacy(MonitorEntity row) {
        if (repository == null) throw new IllegalStateException("Plugin storage unavailable");
        boolean inserted = repository.importLegacy(row);
        if (inserted) imported.add(row);
        return inserted;
    }

    public synchronized void reloadImported() {
        service.restoreImported(List.copyOf(imported));
        imported.clear();
    }

    private volatile boolean ready;

    public AgentWorkSource work() {
        return new AgentWorkSource() {
            public List<Observation> pending(Scope scope) {
                return ready ? service.pending(scope) : List.of();
            }

            public void started(Scope scope, Observation value) {
                service.started(scope, value);
            }

            public void completed(Scope scope, Observation value, boolean success) {
                service.completed(scope, value, success);
            }

            public void cancelled(Scope scope, Observation value) {
                service.cancelled(scope, value);
            }
        };
    }

    public MonitorService service() {
        return service;
    }

    public MonitorOperations operations() {
        return new MonitorOperations() {
            private PluginHost.Invocation scope(String tool) {
                if (host == null) throw new IllegalStateException("Plugin host unavailable");
                return host.invocation(tool);
            }

            public Object create(String purpose, Instant due) {
                var scope = scope("create_monitor");
                return service.createTimer(
                        scope.owner(),
                        scope.sessionId(),
                        scope.agentId(),
                        purpose,
                        due,
                        scope.requestId());
            }

            public Object inspect() {
                var scope = scope("inspect_monitor");
                return service.list(scope.owner(), scope.sessionId()).stream()
                        .filter(row -> row.agentId().equals(scope.agentId()))
                        .toList();
            }

            public Object control(String id, String operation) {
                var scope = scope(operation + "_monitor");
                return service.control(
                        scope.owner(), scope.sessionId(), scope.agentId(), id, operation);
            }
        };
    }

    public void awaitGroup() {
        if (host == null) throw new IllegalStateException("Plugin host unavailable");
        host.await("create_task", service.awaitGroup(host.invocation("create_task")));
    }

    public List<MonitorRecord> storedRecords() {
        var source = repository;
        if (source == null) return List.of();
        var mapper = new ObjectMapper().findAndRegisterModules();
        return source.findAll().stream()
                .map(
                        row -> {
                            try {
                                return mapper.readValue(
                                        row.getPayload(),
                                        ToolDocs.nonNullClass(MonitorRecord.class));
                            } catch (IOException error) {
                                throw new IllegalStateException("Invalid stored monitor", error);
                            }
                        })
                .toList();
    }

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
                        LoggerFactory.getLogger(MonitorRuntime.class)
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
