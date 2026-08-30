package top.focess.veto.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Annotates the complete append-only trace with the compiled-message rewind semantics. */
final class SessionRecordProjector {

    private SessionRecordProjector() {}

    static @NonNull List<@NonNull SessionRecord> project(
            @NonNull List<@NonNull SessionRecord> raw) {
        List<SessionRecord> annotated = new ArrayList<>();
        List<List<Integer>> messageSlots = new ArrayList<>();
        Integer pendingThought = null;

        for (SessionRecord record : raw) {
            switch (record.type()) {
                case "AGENT_INIT" -> annotated.add(record);
                case "ASSISTANT_THOUGHT" -> {
                    if (pendingThought != null) {
                        deactivateTurns(annotated, Set.of(pendingThought), 0);
                    }
                    annotated.add(record);
                    pendingThought = record.turnNumber();
                }
                case "TOOL_CALL", "ASSISTANT_RESPONSE" -> {
                    annotated.add(record);
                    List<Integer> slot = new ArrayList<>();
                    if (pendingThought != null) {
                        slot.add(pendingThought);
                    }
                    slot.add(record.turnNumber());
                    messageSlots.add(List.copyOf(slot));
                    pendingThought = null;
                }
                case "USER_PROMPT", "USER_INTERRUPT", "TOOL_RESPONSE", "COMPACTION_SUMMARY" -> {
                    if (pendingThought != null) {
                        deactivateTurns(annotated, Set.of(pendingThought), 0);
                        pendingThought = null;
                    }
                    annotated.add(record);
                    messageSlots.add(List.of(record.turnNumber()));
                }
                case "REWIND" -> {
                    int removed =
                            dropPendingThought(annotated, pendingThought, record.turnNumber());
                    pendingThought = null;
                    removed +=
                            truncate(
                                    annotated,
                                    messageSlots,
                                    fromIndex(record),
                                    record.turnNumber());
                    SessionRecord rewind = record.withRewoundRecords(removed);
                    annotated.add(rewind);
                    if (hasInjectedContent(record)) {
                        messageSlots.add(List.of(record.turnNumber()));
                    }
                }
                default -> annotated.add(record);
            }
        }

        if (pendingThought != null) {
            messageSlots.add(List.of(pendingThought));
        }
        return List.copyOf(annotated);
    }

    private static int dropPendingThought(
            @NonNull List<SessionRecord> annotated,
            Integer pendingThought,
            int boundaryTurnNumber) {
        if (pendingThought == null) {
            return 0;
        }
        return deactivateTurns(annotated, Set.of(pendingThought), boundaryTurnNumber);
    }

    private static int truncate(
            @NonNull List<SessionRecord> annotated,
            @NonNull List<List<Integer>> messageSlots,
            int fromIndex,
            int boundaryTurnNumber) {
        int keep = Math.max(0, Math.min(fromIndex, messageSlots.size()));
        Set<Integer> removedTurns = new HashSet<>();
        while (messageSlots.size() > keep) {
            removedTurns.addAll(messageSlots.remove(messageSlots.size() - 1));
        }
        return deactivateTurns(annotated, removedTurns, boundaryTurnNumber);
    }

    private static int deactivateTurns(
            @NonNull List<SessionRecord> annotated,
            @NonNull Set<Integer> removedTurns,
            int boundaryTurnNumber) {
        int count = 0;
        for (int index = 0; index < annotated.size(); index++) {
            SessionRecord record = annotated.get(index);
            if (record.active() && removedTurns.contains(record.turnNumber())) {
                annotated.set(index, record.inactiveAfter(boundaryTurnNumber));
                count++;
            }
        }
        return count;
    }

    private static int fromIndex(@NonNull SessionRecord record) {
        Object value = record.payload().get("from_index");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean hasInjectedContent(@NonNull SessionRecord record) {
        Object value = record.payload().get("content");
        return value instanceof String content && !content.isBlank();
    }
}
