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

    public record Entry(@NonNull TurnRecord record, int removedBy, int removedCount) {
        public boolean active() {
            return removedBy == 0;
        }
    }

    public static @NonNull List<Entry> replay(@NonNull List<TurnRecord> history) {
        List<Entry> entries = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        for (TurnRecord turn : history) {
            int removed = 0;
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
                        case AGENT_INIT, ASSISTANT_THOUGHT -> false;
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
        List<TurnRecord> additions = new ArrayList<>();
        int number = lastTurn;
        if (!effective.isEmpty()) additions.add(TurnRecord.rewind(++number, 0));
        additions.add(
                TurnRecord.agentInit(
                        ++number, role.toLowerCase(Locale.ROOT), system, provider, model));
        for (TurnRecord turn : effective) {
            if (turn.type() == TurnType.AGENT_INIT) continue;
            if (turn.type() == TurnType.REWIND) {
                if (turn.payload().get("content") instanceof String content && !content.isBlank())
                    additions.add(TurnRecord.userPrompt(++number, content));
            } else {
                Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
                payload.putIfAbsent("restored_from_turn", turn.turnNumber());
                additions.add(new TurnRecord(++number, turn.type(), payload, null));
            }
        }
        return List.copyOf(additions);
    }

    public static @NonNull List<TurnRecord> effective(@NonNull List<TurnRecord> history) {
        return replay(history).stream().filter(Entry::active).map(Entry::record).toList();
    }
}
