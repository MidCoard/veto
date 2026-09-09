package top.focess.veto.session;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.HistoryProjection;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;

/** Annotates the audit log using the same replay as the model context. */
final class SessionRecordProjector {
    private SessionRecordProjector() {}

    static @NonNull List<@NonNull SessionRecord> project(
            @NonNull List<@NonNull SessionRecord> raw) {
        List<TurnRecord> turns =
                raw.stream()
                        .map(
                                record ->
                                        new TurnRecord(
                                                record.turnNumber(),
                                                type(record.type()),
                                                record.payload(),
                                                record.timestamp()))
                        .toList();
        List<HistoryProjection.Entry> projection = HistoryProjection.replay(turns);
        List<SessionRecord> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            var entry = projection.get(i);
            SessionRecord record = raw.get(i).withRewoundRecords(entry.removedCount());
            result.add(entry.active() ? record : record.inactiveAfter(entry.removedBy()));
        }
        return List.copyOf(result);
    }

    private static @NonNull TurnType type(@NonNull String name) {
        TurnType type = TurnType.valueOf(name);
        if (type == null) throw new IllegalArgumentException("Unknown turn type: " + name);
        return type;
    }
}
