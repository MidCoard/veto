package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Ordered replay shared by prompt compilation, compaction, and the record inspector. */
public final class HistoryProjection {
    private HistoryProjection() {}

    public record Entry(@NonNull TurnRecord record, int removedBy, int removedCount, boolean superseded) {
        public Entry(@NonNull TurnRecord record, int removedBy, int removedCount) {
            this(record, removedBy, removedCount, false);
        }
        public boolean active() {
            return removedBy == 0 && !superseded;
        }
    }

    public static @NonNull List<Entry> replay(@NonNull List<TurnRecord> history) {
        List<Entry> entries = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        for (TurnRecord turn : history) {
            int removed = 0;
            if (turn.type() == TurnType.AGENT_INIT
                    && Boolean.TRUE.equals(turn.payload().get("context_update"))) {
                for (int i = active.size() - 1; i >= 0; i--) {
                    int index = active.get(i);
                    Entry previous = entries.get(index);
                    if (previous.record().type() == TurnType.AGENT_INIT) {
                        entries.set(index, new Entry(previous.record(), 0, previous.removedCount(), true));
                        active.remove(i);
                    }
                }
            }
            if (turn.type() == TurnType.REWIND) {
                Object recordIndex = turn.payload().get("record_index");
                Object messageIndex = turn.payload().get("from_index");
                int keep =
                        recordIndex instanceof Number n
                                ? Math.max(0, Math.min(n.intValue(), active.size()))
                                : legacyBoundary(
                                        entries,
                                        active,
                                        messageIndex instanceof Number n ? n.intValue() : 0);
                while (active.size() > keep) {
                    int index = active.remove(active.size() - 1);
                    Entry previous = entries.get(index);
                    entries.set(
                            index,
                            new Entry(
                                    previous.record(), turn.turnNumber(), previous.removedCount()));
                    removed++;
                }
            }
            active.add(entries.size());
            entries.add(new Entry(turn, 0, removed));
        }
        return List.copyOf(entries);
    }

    /** Legacy indices count conversation messages, excluding initialization and empty rewinds. */
    private static int legacyBoundary(
            @NonNull List<Entry> entries, @NonNull List<Integer> active, int messages) {
        if (messages <= 0) return 0;
        int count = 0;
        int boundary = 0;
        for (int i = 0; i < active.size(); i++) {
            TurnRecord turn = entries.get(active.get(i)).record();
            boolean emits =
                    switch (turn.type()) {
                        case AGENT_INIT, ASSISTANT_THOUGHT, TOKEN_USAGE -> false;
                        case EXECUTION_ERROR ->
                                "CANCELLED".equals(turn.payload().get("outcome"))
                                        && turn.payload().get("requestId") instanceof String;
                        case REWIND ->
                                turn.payload().get("content") instanceof String content
                                        && !content.isBlank();
                        default -> true;
                    };
            if (emits) {
                count++;
                boundary = i + 1;
                if (count == messages) return boundary;
            }
        }
        return boundary;
    }

    /** Explicitly rebuild a changed system context without recording fresh tool executions. */
    public static @NonNull List<TurnRecord> reinitialize(
            @NonNull List<TurnRecord> history,
            int lastTurn,
            @NonNull String role,
            @NonNull String system,
            @NonNull String provider,
            @NonNull String model) {
        List<TurnRecord> effective = effective(history);
        int initCount = 0;
        boolean matches = false;
        for (TurnRecord turn : effective) {
            if (turn.type() == TurnType.AGENT_INIT) {
                initCount++;
                matches =
                        system.equals(turn.payload().get("system_prompt"))
                                && provider.equals(turn.payload().get("provider"))
                                && model.equals(turn.payload().get("model"));
            }
        }
        if (initCount == 1 && matches) return List.of();
        TurnRecord init = TurnRecord.agentInit(
                lastTurn + 1, role.toLowerCase(Locale.ROOT), system, provider, model);
        Map<String, Object> payload = new LinkedHashMap<>(init.payload());
        payload.put("context_update", true);
        return List.of(new TurnRecord(init.turnNumber(), init.type(), payload, init.timestamp()));
    }

    public static @NonNull List<TurnRecord> effective(@NonNull List<TurnRecord> history) {
        return replay(history).stream().filter(Entry::active).map(Entry::record).toList();
    }
}
