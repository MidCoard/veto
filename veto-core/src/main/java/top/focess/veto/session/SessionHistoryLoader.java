package top.focess.veto.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

/**
 * Loads a session's durable conversation log (the existing {@code turn_records} table, written on
 * every turn by {@code MemoryCaptureService}) back into {@link TurnRecord}s for replay into an
 * {@code AgentRunner} on session activate.
 */
@Component
public class SessionHistoryLoader {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryLoader.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final TurnRecordRepository repo;
    private final ObjectMapper mapper;

    public SessionHistoryLoader(TurnRecordRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public @NonNull List<TurnRecord> load(@NonNull String sessionId) {
        List<TurnRecordEntity> rows = repo.findBySessionIdOrderByTurnNumberAsc(sessionId);
        List<TurnRecord> out = new ArrayList<>(rows.size());
        for (TurnRecordEntity row : rows) {
            try {
                TurnType type = TurnType.valueOf(row.getType());
                Map<String, Object> payload = deserializePayload(row.getPayload());
                out.add(new TurnRecord(row.getTurnNumber(), type, payload, row.getTimestamp()));
            } catch (Exception e) {
                // A single bad row must not abort replay of the rest.
                log.warn(
                        "Skipping unparseable turn record {} in session {}",
                        row.getId(),
                        sessionId,
                        e);
            }
        }
        return out;
    }

    private Map<String, Object> deserializePayload(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return mapper.readValue(json, PAYLOAD_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
