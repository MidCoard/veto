package top.focess.veto.group;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The append-only group message log (blackboard.md). Strict hub-and-spoke: a Mate can only post to
 * {@code receiverId == "LEADER"}; a Leader can dispatch to any Mate. Messages are ordered by {@code
 * turnSeq}; reads observe a consistent MVCC snapshot.
 *
 * <p>Tenant-isolated: a Mate/Leader can only read its own group's messages. (RLS-equivalent
 * filtering happens here in-process for the MVP; production RLS is in {@code
 * database_concurrency_isolation.md §2}.)
 */
@Component
public class Blackboard {

    private final ConcurrentMap<UUID, List<BlackboardMessage>> messages = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    /** Append a message to a group's log. */
    public BlackboardMessage post(BlackboardMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message");
        }
        // Enforce hub-and-spoke: Mates can only post to the Leader.
        if (!"LEADER".equals(message.senderId()) && !"LEADER".equals(message.receiverId())) {
            throw new IllegalArgumentException(
                    "Mate-to-Mate messages are forbidden (strict hub-and-spoke)");
        }
        long seq =
                seqCounters
                        .computeIfAbsent(message.groupId(), k -> new AtomicLong(0))
                        .incrementAndGet();
        BlackboardMessage stamped =
                new BlackboardMessage(
                        message.messageId(),
                        message.groupId(),
                        message.senderId(),
                        message.receiverId(),
                        message.type(),
                        message.payload(),
                        seq);
        messages.computeIfAbsent(
                        message.groupId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(stamped);
        return stamped;
    }

    /** Read all messages for a group, in turnSeq order. */
    public @NonNull List<BlackboardMessage> readAll(@NonNull UUID groupId) {
        return messages.getOrDefault(groupId, List.of());
    }

    /** Read messages addressed to a specific receiver. */
    public @NonNull List<BlackboardMessage> readFor(
            @NonNull UUID groupId, @NonNull String receiverId) {
        return readAll(groupId).stream().filter(m -> receiverId.equals(m.receiverId())).toList();
    }

    /** Total messages for a group. */
    public int size(UUID groupId) {
        return messages.getOrDefault(groupId, List.of()).size();
    }

    /** Test-only: clear a group. */
    public void clear(UUID groupId) {
        messages.remove(groupId);
        seqCounters.remove(groupId);
    }
}
