package top.focess.veto.agent;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.identity.AgentPersona;

/** Owns live agents and invocation dependencies independently of group membership. */
@Component
public final class SessionAgentRegistry {
    public record Entry(
            @NonNull UUID sessionId,
            String parentAgentId,
            String parentCallId,
            @NonNull VetoAgent agent) {}

    private final @NonNull Map<@NonNull String, @NonNull Entry> live = new HashMap<>();
    private boolean closed;

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
        if (closed || live.containsKey(entry.agent().id())) {
            throw new IllegalStateException(
                    "Agent registry is closed or agent is already registered");
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
        Entry parent = live.get(parentAgentId);
        if (closed
                || parent == null
                || !parent.sessionId().equals(sessionId)
                || parent.agent().state() == AgentState.TERMINATED) {
            throw new IllegalStateException("Parent agent is no longer active in this session");
        }
        if (live.containsKey(persona.id())) throw new IllegalStateException("Duplicate agent id");
        runner.setSessionId(sessionId);
        VetoAgent child = new VetoAgent(persona, runner);
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
