package top.focess.veto.bus;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSessions;

/**
 * Best-effort resource changes and scoped data publications; never sends sockets under registry
 * locks.
 */
@Component
public class SessionActions {
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull ThreadPoolExecutor delivery =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(512),
                    Thread.ofPlatform().daemon().name("session-actions").factory());

    /** Creates the resource publisher on top of runtime sessions and the agent registry. */
    public SessionActions(
            @NonNull AgentSessions runtime,
            @NonNull AgentInstanceRepository agents,
            @NonNull SessionRepository sessions) {
        this.runtime = runtime;
        this.agents = agents;
        this.sessions = sessions;
    }

    /**
     * Invalidates the named resources of a session; delivery waits for the active transaction to
     * commit, if any.
     */
    public void changed(@NonNull UUID sessionId, @NonNull String @NonNull ... resources) {
        afterCommit(() -> send(sessionId, resources));
    }

    /**
     * Invalidates the named resources of the session owning {@code agentId}; a no-op if the agent
     * is unknown.
     */
    public void agentChanged(@NonNull String agentId, @NonNull String @NonNull ... resources) {
        afterCommit(
                () ->
                        agents.findById(agentId)
                                .ifPresent(
                                        agent ->
                                                send(
                                                        UUID.fromString(agent.getSessionId()),
                                                        resources)));
    }

    private void send(@NonNull UUID sessionId, @NonNull String @NonNull [] resources) {
        sessions.findById(sessionId.toString())
                .ifPresent(
                        session ->
                                runtime.session(
                                                new Scope.SessionScope(
                                                        session.getUserId(), sessionId.toString()))
                                        .send(
                                                new Frame.Action(
                                                        new Scope.SessionScope(
                                                                session.getUserId(),
                                                                sessionId.toString()),
                                                        List.of(resources))));
    }

    /** Queues a scoped publication after commit, without sending under caller locks. */
    public void publish(Frame.@NonNull ScopedFrame frame) {
        var user = frame.scope().userId();
        var sessionId = frame.scope().session();
        if (user == null || sessionId == null)
            throw new IllegalArgumentException("Session output requires an owner and session");
        afterCommit(() -> runtime.session(new Scope.SessionScope(user, sessionId)).send(frame));
    }

    private void afterCommit(@NonNull Runnable action) {
        Runnable enqueue =
                () -> {
                    try {
                        delivery.execute(
                                () -> {
                                    try {
                                        action.run();
                                    } catch (Throwable error) {
                                        report(error);
                                    }
                                });
                    } catch (RuntimeException error) {
                        report(error);
                    }
                };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            enqueue.run();
                        }
                    });
        } else enqueue.run();
    }

    private static void report(@NonNull Throwable error) {
        LoggerFactory.getLogger("top.focess.veto.bus.SessionActions")
                .warn("Resource action delivery failed; clients recover from snapshots", error);
    }

    /** Stops the delivery executor. */
    @PreDestroy
    public void close() {
        delivery.shutdownNow();
    }
}
