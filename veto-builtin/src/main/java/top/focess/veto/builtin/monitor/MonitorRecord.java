package top.focess.veto.builtin.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        List<Event> delivered,
        Map<String, Activation> activations,
        String requestId) {
    public MonitorRecord {
        activations = activations == null ? Map.of() : Map.copyOf(activations);
    }

    /** Compatibility constructor without activation states or request id. */
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
            @NonNull Instant createdAt,
            List<Event> delivered) {
        this(
                id, owner, sessionId, agentId, kind, purpose, sourceId, dueAt, state, seen, pending,
                createdAt, delivered, Map.of());
    }

    /** Compatibility constructor without a request id. */
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
            @NonNull Instant createdAt,
            List<Event> delivered,
            Map<String, Activation> activations) {
        this(
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
                pending,
                createdAt,
                delivered,
                activations,
                null);
    }

    /** Delivery lifecycle state of a single event activation. */
    public enum ActivationState {
        APPENDED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED
    }

    /** Timestamped activation state of one event. */
    public record Activation(@NonNull ActivationState state, @NonNull Instant updatedAt) {}

    /** Activation states keyed by event id; never null. */
    public @NonNull Map<String, Activation> activationStates() {
        return activations == null ? Map.of() : activations;
    }

    /** Returns a copy with the given event moved to the next activation state. */
    public @NonNull MonitorRecord withActivation(
            @NonNull String eventId, @NonNull ActivationState next) {
        var states = new LinkedHashMap<>(activationStates());
        states.put(eventId, new Activation(next, Instant.now()));
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
                pending,
                createdAt,
                deliveredEvents(),
                states,
                requestId);
    }

    /** Events ready for dispatch: pending ones plus delivered ones still in APPENDED state. */
    public @NonNull List<Event> readyEvents() {
        var events = new LinkedHashMap<String, Event>();
        for (Event event : pending) events.put(event.id(), event);
        for (Event event : deliveredEvents()) {
            Activation activation = activationStates().get(event.id());
            if (activation != null && activation.state() == ActivationState.APPENDED)
                events.put(event.id(), event);
        }
        return List.copyOf(events.values());
    }

    /** Returns a copy marking every RUNNING activation as INTERRUPTED. */
    public @NonNull MonitorRecord interruptedActivations() {
        MonitorRecord next = this;
        for (var entry : activationStates().entrySet())
            if (entry.getValue().state() == ActivationState.RUNNING)
                next = next.withActivation(entry.getKey(), ActivationState.INTERRUPTED);
        return next;
    }

    /** Compatibility constructor without delivered events, activations or request id. */
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

    /** Delivered events; never null. */
    public @NonNull List<Event> deliveredEvents() {
        return delivered == null ? List.of() : delivered;
    }

    /** Returns a copy with the event moved from pending to delivered and marked APPENDED. */
    public @NonNull MonitorRecord acknowledge(@NonNull Event event) {
        List<Event> receipt = new ArrayList<>(deliveredEvents());
        if (receipt.stream().noneMatch(e -> e.id().equals(event.id()))) receipt.add(event);
        MonitorRecord next =
                new MonitorRecord(
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
                        List.copyOf(receipt),
                        activationStates(),
                        requestId);
        return activationStates().containsKey(event.id())
                ? next
                : next.withActivation(event.id(), ActivationState.APPENDED);
    }

    /** An observation triggered by this monitor. */
    public record Event(
            @NonNull String id,
            @NonNull String monitorId,
            @NonNull String kind,
            @NonNull String content,
            @NonNull Instant occurredAt,
            String requestId,
            String dispatchId) {
        /** Compatibility constructor without request/dispatch correlation. */
        public Event(
                @NonNull String id,
                @NonNull String monitorId,
                @NonNull String kind,
                @NonNull String content,
                @NonNull Instant occurredAt) {
            this(id, monitorId, kind, content, occurredAt, null, null);
        }
    }

    /** Returns a copy with the given state, seen markers and pending events. */
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
                deliveredEvents(),
                activationStates(),
                requestId);
    }
}
