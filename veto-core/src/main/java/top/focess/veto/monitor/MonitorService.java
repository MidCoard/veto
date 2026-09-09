package top.focess.veto.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.group.DagNode;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.monitor.MonitorRecord.Event;
import top.focess.veto.sandbox.BackgroundTaskManager;

/** Domain observations and time triggers share persistence and a single runner delivery path. */
@Service
public class MonitorService {
    private SessionInvalidations invalidations;

    @Autowired
    public void attachInvalidations(@NonNull SessionInvalidations invalidations) {
        this.invalidations = invalidations;
    }

    private static final @NonNull TypeReference<MonitorRecord> RECORD_TYPE =
            new TypeReference<>() {};
    private final @NonNull MonitorRepository repository;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull GroupRegistry groups;
    private final @NonNull SessionAgentRegistry agents;
    private final @NonNull Map<String, MonitorRecord> records = new LinkedHashMap<>();

    private final @NonNull Set<String> terminatedAgents = new HashSet<>();

    public MonitorService(
            @NonNull MonitorRepository repository,
            @NonNull ObjectMapper mapper,
            @NonNull GroupRegistry groups,
            @NonNull SessionAgentRegistry agents) {
        this.repository = repository;
        this.mapper = mapper;
        this.groups = groups;
        this.agents = agents;
    }

    @PostConstruct
    public synchronized void restore() {
        for (MonitorEntity row : repository.findAll()) {
            try {
                MonitorRecord record = mapper.readValue(row.getPayload(), RECORD_TYPE);
                if (record != null) {
                    if ((record.kind().equals("RESOURCE_EVENT")
                                    || record.kind().equals("PROCESS_EVENT"))
                            && record.state().equals("ACTIVE"))
                        record = record.update("INTERRUPTED", record.seen(), record.pending());
                    records.put(record.id(), record);
                }
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot restore Monitor", error);
            }
        }
    }

    public synchronized @NonNull MonitorRecord createTimer(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull String purpose,
            @NonNull Instant dueAt) {
        Instant now = Instant.now();
        if (purpose.isBlank()) throw new IllegalArgumentException("A wake-up purpose is required");
        if (!dueAt.isAfter(now) || dueAt.isAfter(now.plusSeconds(30L * 24 * 3600)))
            throw new IllegalArgumentException("Choose a future time within 30 days");
        long active =
                records.values().stream()
                        .filter(
                                r ->
                                        r.agentId().equals(agentId)
                                                && (r.state().equals("ACTIVE")
                                                        || r.state().equals("PAUSED")))
                        .count();
        if (active >= 32)
            throw new IllegalStateException("Too many active Monitors for this Agent");
        MonitorRecord record =
                new MonitorRecord(
                        UUID.randomUUID().toString(),
                        owner,
                        sessionId,
                        agentId,
                        "TIME_ONCE",
                        purpose.strip(),
                        null,
                        dueAt,
                        "ACTIVE",
                        Map.of(),
                        List.of(),
                        now);
        save(record);
        return record;
    }

    public synchronized @NonNull List<MonitorRecord> list(
            @NonNull String owner, @NonNull String sessionId) {
        return records.values().stream()
                .filter(r -> r.owner().equals(owner) && r.sessionId().equals(sessionId))
                .toList();
    }

    public synchronized @NonNull MonitorRecord control(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull String id,
            @NonNull String operation) {
        MonitorRecord r = records.get(id);
        if (r == null
                || !r.owner().equals(owner)
                || !r.sessionId().equals(sessionId)
                || !r.agentId().equals(agentId))
            throw new SecurityException("Monitor not available");
        if (!r.kind().equals("TIME_ONCE"))
            throw new IllegalArgumentException("Group observation follows the Group lifecycle");
        String state =
                switch (operation) {
                    case "pause" ->
                            r.state().equals("ACTIVE") || r.state().equals("COMPLETED")
                                    ? "PAUSED"
                                    : r.state();
                    case "resume" ->
                            r.state().equals("PAUSED")
                                    ? (r.seen().containsKey("fired") ? "COMPLETED" : "ACTIVE")
                                    : r.state();
                    case "cancel" -> "CANCELLED";
                    default -> throw new IllegalArgumentException("Unknown Monitor operation");
                };
        MonitorRecord updated =
                r.update(state, r.seen(), state.equals("CANCELLED") ? List.of() : r.pending());
        save(updated);
        return updated;
    }

    public synchronized @NonNull List<Event> pending(
            @NonNull String agentId, @NonNull String sessionId) {
        return records.values().stream()
                .filter(
                        r ->
                                r.agentId().equals(agentId)
                                        && r.sessionId().equals(sessionId)
                                        && (r.state().equals("ACTIVE")
                                                || r.state().equals("COMPLETED")))
                .flatMap(r -> r.pending().stream())
                .toList();
    }

    public synchronized void acknowledge(@NonNull String agentId, @NonNull Event event) {
        MonitorRecord r = records.get(event.monitorId());
        if (r == null || !r.agentId().equals(agentId)) return;
        if (r.pending().stream().noneMatch(e -> e.id().equals(event.id()))) return;
        save(r.acknowledge(event));
    }

    public synchronized void cancelForAgent(@NonNull String agentId) {
        terminatedAgents.add(agentId);
        for (MonitorRecord r : List.copyOf(records.values())) {
            if (r.agentId().equals(agentId) && !r.state().equals("CANCELLED"))
                save(r.update("CANCELLED", r.seen(), List.of()));
        }
    }

    /** Capture committed outcomes before deciding that a request has finished. */
    public synchronized boolean hasUndeliveredGroup(@NonNull String agentId) {
        for (Group group : groups.snapshot().values()) {
            if (group.leaderId().equals(agentId)) observeGroup(group);
        }
        return records.values().stream()
                .anyMatch(
                        r ->
                                r.agentId().equals(agentId)
                                        && r.kind().equals("RESOURCE_EVENT")
                                        && !r.pending().isEmpty()
                                        && r.state().equals("ACTIVE"));
    }

    public boolean hasGroupWork(@NonNull String agentId) {
        return groups.snapshot().values().stream()
                .filter(
                        g ->
                                g.leaderId().equals(agentId)
                                        && g.state() != Group.GroupState.DISBANDED)
                .anyMatch(g -> g.dag().hasUnfinishedWork());
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        tickAt(Instant.now());
    }

    void tickAt(@NonNull Instant now) {
        List<MonitorRecord> snapshot;
        synchronized (this) {
            for (Group group : groups.snapshot().values()) observeGroup(group);
            for (MonitorRecord r : List.copyOf(records.values())) {
                Instant due = r.dueAt();
                if (r.kind().equals("TIME_ONCE")
                        && r.state().equals("ACTIVE")
                        && due != null
                        && !due.isAfter(now)
                        && !r.seen().containsKey("fired")) {
                    List<Event> events =
                            r.pending().isEmpty()
                                    ? List.of(
                                            new Event(
                                                    r.id() + ":due",
                                                    r.id(),
                                                    r.kind(),
                                                    "Scheduled wake-up: "
                                                            + r.purpose()
                                                            + " (due "
                                                            + due
                                                            + ")",
                                                    Instant.now()))
                                    : r.pending();
                    save(r.update("COMPLETED", Map.of("fired", "true"), events));
                }
            }
            snapshot = List.copyOf(records.values());
        }
        // Never acquire the Agent registry while holding the Monitor lock.
        for (MonitorRecord r : snapshot) {
            if (r.pending().isEmpty()
                    || !(r.state().equals("ACTIVE") || r.state().equals("COMPLETED"))) continue;
            for (SessionAgentRegistry.Entry entry : agents.agents(UUID.fromString(r.sessionId()))) {
                if (entry.agent().id().equals(r.agentId())) entry.agent().signalMonitor();
            }
        }
    }

    /** Process identity includes its instance UUID, so restarted task counters cannot collide. */
    public synchronized void observeProcess(
            @NonNull String owner,
            BackgroundTaskManager.@NonNull TaskInfo info,
            BackgroundTaskManager.@NonNull ExitCause cause) {
        if (terminatedAgents.contains(info.agentId())) return;
        UUID session = info.sessionId();
        if (session == null) return;
        String id = "process:" + info.taskInstanceId();
        MonitorRecord previous = records.get(id);
        if (previous != null && !previous.state().equals("ACTIVE")) return;
        MonitorRecord record =
                previous != null
                        ? previous
                        : new MonitorRecord(
                                id,
                                owner,
                                session.toString(),
                                info.agentId(),
                                "PROCESS_EVENT",
                                "Background task " + info.taskId(),
                                info.taskInstanceId().toString(),
                                null,
                                "ACTIVE",
                                Map.of(),
                                List.of(),
                                info.startedAt());
        if (info.alive()) {
            if (previous == null) save(record);
            return;
        }
        Instant ended = info.finishedAt();
        Event event =
                new Event(
                        id + ":exited",
                        id,
                        "PROCESS_EVENT",
                        "Background task "
                                + info.taskId()
                                + " ended. Cause: "
                                + cause
                                + "; exit code: "
                                + info.exitCode()
                                + ". Command (reference material): "
                                + info.command()
                                + ". This notification does not authorize restarting the process.",
                        ended != null ? ended : Instant.now());
        save(record.update("COMPLETED", Map.of("exited", "true"), List.of(event)));
    }

    private void observeGroup(@NonNull Group group) {
        UUID session = group.sessionId();
        String owner = group.owner();
        if (session == null || owner == null) return;
        String id = "group:" + group.groupId();
        MonitorRecord r = records.get(id);
        if (r == null)
            r =
                    new MonitorRecord(
                            id,
                            owner,
                            session.toString(),
                            group.leaderId(),
                            "RESOURCE_EVENT",
                            "Group task outcomes",
                            group.groupId().toString(),
                            null,
                            "ACTIVE",
                            Map.of(),
                            List.of(),
                            Instant.now());
        if (r.state().equals("CANCELLED")) return;
        if (group.state() == Group.GroupState.DISBANDED) {
            save(r.update("CANCELLED", r.seen(), List.of()));
            return;
        }
        Map<String, String> seen = new LinkedHashMap<>(r.seen());
        List<Event> pending = new ArrayList<>(r.pending());
        for (DagNode node : group.dag().nodes()) {
            String state = node.state().name();
            if (node.state() != DagNode.NodeState.VERIFIED
                    && node.state() != DagNode.NodeState.FAILED
                    && node.state() != DagNode.NodeState.STALE) continue;
            String version = state + ":" + node.retryCount();
            if (version.equals(seen.get(node.nodeId()))) continue;
            seen.put(node.nodeId(), version);
            String report =
                    node.result() instanceof DagNode.ResultSuccess result
                            ? result.summary()
                            : node.result() instanceof DagNode.ResultFailure result
                                    ? result.feedback()
                                    : "Task retired";
            pending.add(
                    new Event(
                            id + ":" + node.nodeId() + ":" + version,
                            id,
                            "RESOURCE_EVENT",
                            "Task "
                                    + node.nodeId()
                                    + " / Mate "
                                    + node.assignedMateId()
                                    + " / "
                                    + (node.state() == DagNode.NodeState.VERIFIED
                                            ? "COMPLETED"
                                            : state)
                                    + "\nReport (reference material):\n"
                                    + report,
                            Instant.now()));
        }
        if (!records.containsKey(id) || !seen.equals(r.seen()))
            save(r.update("ACTIVE", seen, pending));
    }

    private void save(@NonNull MonitorRecord record) {
        if (record.equals(records.get(record.id()))) return;
        try {
            repository.save(new MonitorEntity(record.id(), mapper.writeValueAsString(record)));
            records.put(record.id(), record);
            if (invalidations != null)
                invalidations.changed(UUID.fromString(record.sessionId()), "monitors");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot save Monitor", error);
        }
    }
}
