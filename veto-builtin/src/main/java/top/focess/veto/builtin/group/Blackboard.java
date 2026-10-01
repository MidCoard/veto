package top.focess.veto.builtin.group;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;

/**
 * The append-only group message log. Strict hub-and-spoke: a Mate can only post to {@code
 * receiverId == "LEADER"}; a Leader can dispatch to any Mate. Messages are ordered by {@code
 * turnSeq}; reads observe a consistent snapshot.
 *
 * <p>Reads are keyed by group identity. The calling builtin operations enforce the caller's group
 * authority before accessing this log.
 *
 * <p>Mate polling, group ticks and tool calls may run concurrently. Each group log monitor protects
 * message deduplication, sequence allocation and snapshot reads. The board monitor only coordinates
 * change notifications; publication releases the log monitor before signalling it. Wait predicates
 * run under the board monitor and must not acquire orchestrator group locks or block. Clearing a
 * log requires its producers to be quiescent.
 */
public class Blackboard {

    private static final class Log {
        final @NonNull Map<String, BlackboardMessage> messages = new LinkedHashMap<>();
        long sequence;
    }

    private final @NonNull ConcurrentMap<UUID, Log> logs = new ConcurrentHashMap<>();

    /** Append atomically; retrying a published message id returns its original sequence. */
    // The local log is the stable shared monitor for this group's publication and reads.
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public @NonNull BlackboardMessage post(@NonNull BlackboardMessage message) {
        // Enforce hub-and-spoke: Mates can only post to the Leader.
        if (!"LEADER".equals(message.senderId()) && !"LEADER".equals(message.receiverId())) {
            throw new IllegalArgumentException(
                    "Mate-to-Mate messages are forbidden (strict hub-and-spoke)");
        }
        var log = logs.computeIfAbsent(message.groupId(), ignored -> new Log());
        BlackboardMessage stamped;
        synchronized (log) {
            var previous = log.messages.get(message.messageId());
            if (previous != null) return previous;
            stamped =
                    new BlackboardMessage(
                            message.messageId(),
                            message.groupId(),
                            message.senderId(),
                            message.receiverId(),
                            message.type(),
                            message.payload(),
                            ++log.sequence,
                            message.dispatchId());
            log.messages.put(stamped.messageId(), stamped);
        }
        signalChange();
        return stamped;
    }

    /** Waits for a message or group-state condition without polling. */
    public synchronized void awaitChange(@NonNull BooleanSupplier ready, long timeoutNanos)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        long remaining = timeoutNanos;
        while (!ready.getAsBoolean() && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
    }

    /** Wakes readers after messages or group state change. */
    public synchronized void signalChange() {
        notifyAll();
    }

    /** Read all messages for a group, in turnSeq order. */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public @NonNull List<BlackboardMessage> readAll(@NonNull UUID groupId) {
        var log = logs.get(groupId);
        if (log == null) return List.of();
        synchronized (log) {
            return List.copyOf(log.messages.values());
        }
    }

    /** Read messages addressed to a specific receiver. */
    public @NonNull List<BlackboardMessage> readFor(
            @NonNull UUID groupId, @NonNull String receiverId) {
        return readAll(groupId).stream().filter(m -> receiverId.equals(m.receiverId())).toList();
    }

    /** Total messages for a group. */
    public int size(@NonNull UUID groupId) {
        return readAll(groupId).size();
    }

    /** Test-only: clear a group. */
    public void clear(@NonNull UUID groupId) {
        logs.remove(groupId);
    }
}
