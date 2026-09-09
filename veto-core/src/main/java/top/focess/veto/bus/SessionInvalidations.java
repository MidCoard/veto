package top.focess.veto.bus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.model.AgentInstanceRepository;

/**
 * Best-effort invalidations after readable state changes; never sends sockets under registry locks.
 */
@Component
public class SessionInvalidations {
    private final @NonNull DeltaBroker broker;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull ThreadPoolExecutor delivery =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(512),
                    Thread.ofPlatform().daemon().name("session-invalidations").factory());

    public SessionInvalidations(
            @NonNull DeltaBroker broker, @NonNull AgentInstanceRepository agents) {
        this.broker = broker;
        this.agents = agents;
    }

    public void changed(@NonNull UUID sessionId, @NonNull String @NonNull ... resources) {
        afterCommit(() -> send(sessionId, resources));
    }

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
        var values = JsonNodeFactory.instance.arrayNode();
        for (String resource : resources) values.add(resource);
        broker.publish(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.SESSION_INVALIDATED)
                        .attr("resources", values)
                        .build());
    }

    private void afterCommit(@NonNull Runnable action) {
        Runnable enqueue =
                () -> {
                    try {
                        delivery.execute(
                                () -> {
                                    try {
                                        action.run();
                                    } catch (RuntimeException error) {
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

    private static void report(@NonNull RuntimeException error) {
        LoggerFactory.getLogger("top.focess.veto.bus.SessionInvalidations")
                .warn(
                        "Session invalidation delivery failed; clients recover from snapshots",
                        error);
    }

    @PreDestroy
    public void close() {
        delivery.shutdownNow();
    }
}
