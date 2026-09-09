package top.focess.veto.agent;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.monitor.MonitorService;

/** Owns live agents and invocation dependencies independently of group membership. */
@Component
public final class SessionAgentRegistry {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.SessionAgentRegistry");

    private MonitorService monitorService;

    @Autowired
    public void attachMonitor(@Lazy @NonNull MonitorService service) {
        this.monitorService = service;
    }

    public record Entry(
            @NonNull UUID sessionId,
            String parentAgentId,
            String parentCallId,
            @NonNull VetoAgent agent) {}

    private final @NonNull Map<@NonNull String, @NonNull Entry> live = new HashMap<>();
    private boolean closed;
    private final AgentInstanceRepository repository;
    private final TurnRecordRepository turns;

    /** Embedded runners without a database still have runtime lifecycle ownership. */
    public SessionAgentRegistry() {
        repository = null;
        turns = null;
    }

    @Autowired
    public SessionAgentRegistry(
            @NonNull AgentInstanceRepository repository, @NonNull TurnRecordRepository turns) {
        this.repository = repository;
        this.turns = turns;
    }

    public record AgentSummary(
            @NonNull String id,
            @NonNull String name,
            Role role,
            AgentState state,
            String parentAgentId,
            String parentCallId,
            boolean live,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            String responsibility,
            boolean userInteractionEnabled) {}

    /** Session membership survives runtime cleanup; histories remain in their own streams. */
    public synchronized @NonNull List<@NonNull AgentSummary> records(@NonNull UUID sessionId) {
        Map<@NonNull String, @NonNull AgentSummary> result = new LinkedHashMap<>();
        if (repository != null) {
            for (AgentEntity entity : repository.findBySessionId(sessionId.toString())) {
                String role = entity.getRuntimeRole();
                result.put(
                        entity.getId(),
                        new AgentSummary(
                                entity.getId(),
                                entity.getName(),
                                role == null ? null : Role.valueOf(role),
                                entity.getEndedAt() == null ? null : AgentState.TERMINATED,
                                entity.getParentAgentId(),
                                entity.getParentCallId(),
                                false,
                                entity.getCreatedAt(),
                                entity.getStartedAt(),
                                entity.getEndedAt(),
                                entity.getResponsibility(),
                                entity.isUserInteractionEnabled()));
            }
        }
        if (turns != null) {
            for (String id : turns.findAgentIdsBySessionId(sessionId.toString())) {
                if (id != null && !id.isBlank() && !"legacy".equals(id)) {
                    result.putIfAbsent(
                            id,
                            new AgentSummary(
                                    id, id, null, null, null, null, false, null, null, null, null,
                                    false));
                }
            }
        }
        for (Entry entry : agents(sessionId)) {
            VetoAgent agent = entry.agent();
            AgentSummary saved = result.get(agent.id());
            result.put(
                    agent.id(),
                    new AgentSummary(
                            agent.id(),
                            saved == null ? agent.name() : saved.name(),
                            agent.persona().role(),
                            agent.state(),
                            entry.parentAgentId(),
                            entry.parentCallId(),
                            true,
                            saved == null ? null : saved.createdAt(),
                            saved == null ? null : saved.startedAt(),
                            null,
                            agent.persona().description(),
                            agent.userInteractionEnabled()));
        }
        return result.values().stream().sorted(Comparator.comparing(AgentSummary::id)).toList();
    }

    public synchronized @NonNull VetoAgent start(
            @NonNull AgentPersona persona, @NonNull AgentRunner runner) {
        if (closed || live.containsKey(persona.id())) {
            throw new IllegalStateException(
                    "Agent registry is closed or agent is already registered");
        }
        VetoAgent agent = new VetoAgent(persona, runner);
        register(new Entry(runner.sessionId(), null, null, agent));
        return agent;
    }

    public synchronized void register(@NonNull UUID sessionId, @NonNull VetoAgent agent) {
        register(new Entry(sessionId, null, null, agent));
    }

    /** Adds an independent agent only while its existing session is still active. */
    public synchronized @NonNull VetoAgent startInSession(
            @NonNull UUID sessionId, @NonNull AgentPersona persona, @NonNull AgentRunner runner) {
        if (live.values().stream().noneMatch(entry -> entry.sessionId().equals(sessionId))) {
            throw new IllegalStateException("Session is no longer active");
        }
        runner.setSessionId(sessionId);
        return start(persona, runner);
    }

    private void register(@NonNull Entry entry) {
        if (monitorService != null) entry.agent().attachMonitor(monitorService);
        if (closed || live.containsKey(entry.agent().id())) {
            throw new IllegalStateException(
                    "Agent registry is closed or agent is already registered");
        }
        if (repository != null) {
            try {
                AgentEntity entity =
                        repository
                                .findById(entry.agent().id())
                                .orElseGet(
                                        () ->
                                                AgentEntity.spawned(
                                                        entry.agent().id(),
                                                        entry.sessionId().toString(),
                                                        entry.agent().name()));
                if (!entity.getSessionId().equals(entry.sessionId().toString())) {
                    throw new IllegalStateException("Agent belongs to another session");
                }
                entity.started(
                        entry.agent().persona(), entry.parentAgentId(), entry.parentCallId());
                entity.setUserInteractionEnabled(entry.agent().userInteractionEnabled());
                repository.save(entity);
            } catch (RuntimeException error) {
                entry.agent().terminate();
                throw error;
            }
        }
        live.put(entry.agent().id(), entry);
        entry.agent().onTermination(() -> stop(entry.agent().id()));
        if (entry.agent().state() == AgentState.TERMINATED) stop(entry.agent().id());
    }

    /** Registration and parent termination are serialized so a late child cannot escape cleanup. */
    public synchronized @NonNull VetoAgent startChild(
            @NonNull UUID sessionId,
            @NonNull String parentAgentId,
            @NonNull String parentCallId,
            @NonNull AgentPersona persona,
            @NonNull AgentRunner runner) {
        return startChild(sessionId, parentAgentId, parentCallId, persona, runner, false);
    }

    public synchronized @NonNull VetoAgent startChild(
            @NonNull UUID sessionId,
            @NonNull String parentAgentId,
            @NonNull String parentCallId,
            @NonNull AgentPersona persona,
            @NonNull AgentRunner runner,
            boolean userInteractionEnabled) {
        Entry parent = live.get(parentAgentId);
        if (closed
                || parent == null
                || !parent.sessionId().equals(sessionId)
                || parent.agent().state() == AgentState.TERMINATED) {
            throw new IllegalStateException("Parent agent is no longer active in this session");
        }
        if (live.containsKey(persona.id())) throw new IllegalStateException("Duplicate agent id");
        runner.setSessionId(sessionId);
        VetoAgent child = new VetoAgent(persona, runner, userInteractionEnabled);
        register(new Entry(sessionId, parentAgentId, parentCallId, child));
        return child;
    }

    /** Metadata only; child histories are never merged into their parent's context. */
    public synchronized @NonNull List<@NonNull Entry> agents(@NonNull UUID sessionId) {
        return live.values().stream().filter(entry -> entry.sessionId().equals(sessionId)).toList();
    }

    public synchronized void stop(@NonNull String agentId) {
        Entry entry = live.remove(agentId);
        if (entry == null) return;
        var children =
                live.values().stream()
                        .filter(child -> agentId.equals(child.parentAgentId()))
                        .map(child -> child.agent().id())
                        .toList();
        children.forEach(this::stop);
        entry.agent().terminate();
        if (monitorService != null && !closed) monitorService.cancelForAgent(agentId);
        var store = repository;
        if (store != null) {
            try {
                AgentEntity entity = store.findById(agentId).orElse(null);
                if (entity != null) {
                    entity.ended(entry.agent().persona());
                    store.save(entity);
                }
            } catch (RuntimeException error) {
                log.warn(
                        "Could not save stop time for agent {}; its identity remains recorded",
                        agentId,
                        error);
            }
        }
    }

    public synchronized void stopSession(@NonNull UUID sessionId) {
        agents(sessionId).forEach(entry -> stop(entry.agent().id()));
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        List.copyOf(live.keySet()).forEach(this::stop);
    }
}
