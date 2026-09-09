package top.focess.veto.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** A persisted rule and its pending observations; triggering and delivery are separate. */
public record MonitorRecord(
        @NonNull String id,
        @NonNull String owner,
        @NonNull String sessionId,
        @NonNull String agentId,
        @NonNull String kind,
        @NonNull String purpose,
        String sourceId,
        Instant dueAt,
        @NonNull String state,
        @NonNull Map<String, String> seen,
        @NonNull List<Event> pending,
        @NonNull Instant createdAt,
        List<Event> delivered) {
    public MonitorRecord(
            @NonNull String id,
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull String kind,
            @NonNull String purpose,
            String sourceId,
            Instant dueAt,
            @NonNull String state,
            @NonNull Map<String, String> seen,
            @NonNull List<Event> pending,
            @NonNull Instant createdAt) {
        this(
                id, owner, sessionId, agentId, kind, purpose, sourceId, dueAt, state, seen, pending,
                createdAt, List.of());
    }

    public @NonNull List<Event> deliveredEvents() {
        return delivered == null ? List.of() : delivered;
    }

    public @NonNull MonitorRecord acknowledge(@NonNull Event event) {
        List<Event> receipt = new ArrayList<>(deliveredEvents());
        if (receipt.stream().noneMatch(e -> e.id().equals(event.id()))) receipt.add(event);
        return new MonitorRecord(
                id,
                owner,
                sessionId,
                agentId,
                kind,
                purpose,
                sourceId,
                dueAt,
                state,
                seen,
                pending.stream().filter(e -> !e.id().equals(event.id())).toList(),
                createdAt,
                List.copyOf(receipt));
    }

    public record Event(
            @NonNull String id,
            @NonNull String monitorId,
            @NonNull String kind,
            @NonNull String content,
            @NonNull Instant occurredAt) {}

    public @NonNull MonitorRecord update(
            @NonNull String nextState,
            @NonNull Map<String, String> nextSeen,
            @NonNull List<Event> events) {
        return new MonitorRecord(
                id,
                owner,
                sessionId,
                agentId,
                kind,
                purpose,
                sourceId,
                dueAt,
                nextState,
                Map.copyOf(nextSeen),
                List.copyOf(events),
                createdAt,
                deliveredEvents());
    }
}
