package top.focess.veto.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * every turn by {@code TurnLogService}) back into {@link TurnRecord}s for replay into an {@code
 * AgentRunner} on session activate.
 */
@Component
public class SessionHistoryLoader {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.session.SessionHistoryLoader");
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final @NonNull TurnRecordRepository repo;
    private final @NonNull ObjectMapper mapper;

    public SessionHistoryLoader(@NonNull TurnRecordRepository repo, @NonNull ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public @NonNull List<TurnRecord> load(@NonNull String sessionId) {
        List<TurnRecordEntity> rows = repo.findBySessionIdOrderByTurnNumberAsc(sessionId);
        return mapRows(rows, sessionId);
    }

    /**
     * Loads one agent's turn stream within a session (the per-agent replay path). A group's Leader
     * and each Mate each own a distinct stream; the caller resolves the agent id (the {@code
     * primary_agent_id} for a Leader) so only that agent's turns are replayed into its runner.
     * Served by the composite index without a full-table scan.
     */
    public @NonNull List<TurnRecord> load(@NonNull String sessionId, @NonNull String agentId) {
        List<TurnRecordEntity> rows =
                repo.findBySessionIdAndAgentIdOrderByTurnNumberAsc(sessionId, agentId);
        return mapRows(rows, sessionId);
    }

    private @NonNull List<TurnRecord> mapRows(
            @NonNull List<TurnRecordEntity> rows, @NonNull String sessionId) {
        List<TurnRecord> out = new ArrayList<>(rows.size());
        for (TurnRecordEntity row : rows) {
            try {
                Map<String, Object> payload = deserializePayload(row.getPayload());
                TurnType type = canonicalType(row.getType());
                if ("SYSTEM_PROMPT".equals(row.getType())) {
                    payload = canonicalAgentInitPayload(payload);
                }
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

    private @NonNull Map<String, Object> deserializePayload(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return mapper.readValue(json, PAYLOAD_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static @NonNull TurnType canonicalType(@NonNull String storedType) {
        return switch (storedType) {
            case "SYSTEM_PROMPT" -> TurnType.AGENT_INIT;
            case "RECALL" -> TurnType.REWIND;
            default -> top.focess.veto.util.Nullness.requireNonNull(TurnType.valueOf(storedType));
        };
    }

    private static @NonNull Map<String, Object> canonicalAgentInitPayload(
            @NonNull Map<String, Object> legacy) {
        Map<String, Object> canonical = new LinkedHashMap<>(legacy);
        Object content = canonical.remove("content");
        if (content != null) {
            canonical.putIfAbsent("system_prompt", content);
        }
        return canonical;
    }
}
