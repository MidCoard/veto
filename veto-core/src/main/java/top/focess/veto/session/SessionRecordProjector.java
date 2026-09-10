package top.focess.veto.session;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.HistoryProjection;
import top.focess.veto.agent.RecordUsage;
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
        return projectContent(RecordUsage.contentRecords(turns), result);
    }

    private static @NonNull List<@NonNull SessionRecord> projectContent(
            @NonNull List<@NonNull TurnRecord> normalized,
            @NonNull List<@NonNull SessionRecord> result) {
        List<SessionRecord> content = new ArrayList<>();
        for (TurnRecord turn : normalized) {
            for (SessionRecord record : result) {
                if (record.turnNumber() == turn.turnNumber()) {
                    content.add(
                            new SessionRecord(
                                    record.agentId(),
                                    record.turnNumber(),
                                    record.type(),
                                    turn.payload(),
                                    record.timestamp(),
                                    record.active(),
                                    record.rewoundByTurnNumber(),
                                    record.rewoundRecords()));
                    break;
                }
            }
        }
        return List.copyOf(content);
    }

    // Generated enum valueOf returns a constant or throws; it never returns null.
    @SuppressWarnings("nullness:return")
    private static @NonNull TurnType type(@NonNull String name) {
        return TurnType.valueOf(name);
    }
}
