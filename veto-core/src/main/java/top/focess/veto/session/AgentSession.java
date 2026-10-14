package top.focess.veto.session;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.AgentEventSink;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;

/**
 * Runtime application session, independent of any socket or runner thread. Attachments and closure
 * are guarded by the owning AgentSessions monitor. Execution remains owned by each agent runner; no
 * model call, socket write or result wait runs under the attachment monitor. Publications are
 * thread-safe; delta numbering is atomic, but concurrent delivery can arrive out of order.
 */
public final class AgentSession {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.session.AgentSession");
    private final @NonNull AgentSessions owner;
    private final Scope.@NonNull SessionScope scope;
    private final @NonNull Supplier<@NonNull AgentService> agents;
    final @NonNull Map<@NonNull ProtocolConnection, @NonNull Attachment> connections =
            new HashMap<>();
    private final @NonNull AtomicLong sequence = new AtomicLong();
    boolean closed;

    AgentSession(
            @NonNull AgentSessions owner,
            Scope.@NonNull SessionScope scope,
            @NonNull Supplier<@NonNull AgentService> agents) {
        this.owner = owner;
        this.scope = scope;
        this.agents = agents;
    }

    public Scope.@NonNull SessionScope scope() {
        return scope;
    }

    public @NonNull RequestHandle submit(
            @NonNull UUID user,
            @NonNull String prompt,
            @NonNull LlmBinding binding,
            @NonNull Locale locale,
            RequestHandle.@NonNull Admission admission) {
        return primary(user).submitRequest(prompt, binding, locale, null, admission);
    }

    public @NonNull VetoAgent agent(@NonNull UUID user, @NonNull String agentId) {
        requireOwner(user);
        return agents.get().sessionAgent(scope.session(), agentId);
    }

    public @NonNull RequestHandle submit(
            @NonNull UUID user,
            @NonNull String agentId,
            @NonNull String prompt,
            @NonNull Locale locale,
            RequestHandle.@NonNull Admission admission) {
        return agent(user, agentId).submitUserPrompt(prompt, locale, admission);
    }

    public @NonNull RequestHandle compact(
            @NonNull UUID user,
            @NonNull Locale locale,
            RequestHandle.@NonNull Admission admission) {
        return primary(user).compact(locale, admission);
    }

    public @NonNull VetoAgent primary(@NonNull UUID user) {
        var agent = agents.get().agent(scope.session());
        requireOwner(user);
        if (agent == null) throw new IllegalStateException("Session agent is unavailable");
        return agent;
    }

    public boolean resolveVeto(@NonNull UUID user, Frame.@NonNull VetoResult result) {
        requireOwner(user);
        if (!scope.equals(result.scope().sessionScope())) return false;
        if (!agents.get().hasApprovalAgent(scope.session(), result.scope().agent())) return false;
        return agents.get()
                .resolveVeto(
                        result.scope().agent(), result.callId(), result.option(), result.seq());
    }

    /** Declines pending approvals from every live agent without creating an execution. */
    public int declineAllVetoes(@NonNull UUID user) {
        requireOwner(user);
        return agents.get().declineSessionVetoes(scope.session());
    }

    /** An agent-bound publication port; producing identity is checked before session delivery. */
    public @NonNull AgentEventSink sink(Scope.@NonNull AgentScope agent) {
        if (!scope.equals(agent.sessionScope()))
            throw new IllegalArgumentException("Agent belongs to another session");
        return new AgentEventSink() {
            @Override
            public void publish(Frame.@NonNull ScopedFrame frame) {
                boolean owned =
                        frame instanceof Frame.Action
                                ? scope.equals(frame.scope())
                                : agent.equals(frame.scope());
                if (!owned)
                    throw new IllegalArgumentException("Frame does not belong to this agent");
                send(frame);
            }

            @Override
            public void publish(Scope.@NonNull AgentScope producer, @NonNull VetoPrompt prompt) {
                if (!agent.equals(producer))
                    throw new IllegalArgumentException("Approval does not belong to this agent");
                send(producer, prompt);
            }
        };
    }

    /** Sends only session output; connection-local replies use ProtocolConnection directly. */
    public void send(Frame.@NonNull ScopedFrame frame) {
        if (!scope.userId().equals(frame.scope().userId())
                || !scope.session().equals(frame.scope().session())
                || !(frame instanceof Frame.ServerFrame outgoing))
            throw new IllegalArgumentException("Frame does not belong to this session");
        Frame.ServerFrame sequenced =
                outgoing instanceof Delta delta
                        ? new Delta(
                                delta.scope(),
                                sequence.incrementAndGet(),
                                delta.emittedAt(),
                                delta.data())
                        : outgoing;
        broadcast((connection, attached) -> connection.sendSession(sequenced, attached));
    }

    public void send(Scope.@NonNull AgentScope agent, @NonNull VetoPrompt prompt) {
        if (!scope.equals(agent.sessionScope()))
            throw new IllegalArgumentException("Approval belongs to another session");
        broadcast((connection, attached) -> connection.send(agent, prompt, attached));
    }

    private void broadcast(
            @NonNull BiPredicate<@NonNull ProtocolConnection, @NonNull BooleanSupplier> send) {
        List<@NonNull Attachment> recipients;
        synchronized (owner) {
            if (closed) return;
            recipients = List.copyOf(connections.values());
        }
        for (var recipient : recipients) {
            var connection = recipient.connection();
            try {
                if (!send.test(connection, () -> attached(connection, recipient)))
                    log.debug("Session {} connection declined output", scope.session());
            } catch (RuntimeException failure) {
                log.warn(
                        "Session {} connection rejected output ({})",
                        scope.session(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private boolean attached(
            @NonNull ProtocolConnection connection, @NonNull Attachment attachment) {
        synchronized (owner) {
            return !closed
                    && connections.get(connection) == attachment
                    && scope.userId().equals(connection.userId());
        }
    }

    record Attachment(@NonNull ProtocolConnection connection) {}

    void requireAttached(@NonNull ProtocolConnection connection) {
        synchronized (owner) {
            if (closed
                    || !connections.containsKey(connection)
                    || !scope.userId().equals(connection.userId()))
                throw new IllegalStateException("Connection is not attached to this session");
        }
    }

    private void requireOwner(@NonNull UUID user) {
        synchronized (owner) {
            if (closed || !scope.userId().equals(user))
                throw new IllegalStateException("Session is unavailable");
        }
    }
}
