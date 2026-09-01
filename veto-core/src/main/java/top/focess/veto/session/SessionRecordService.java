package top.focess.veto.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

/** Builds the complete rewind-annotated, multi-agent session trace consumed by veto-ui. */
@Service
public class SessionRecordService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.session.SessionRecordService");
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final @NonNull TurnRecordRepository repository;
    private final @NonNull ObjectMapper mapper;

    public SessionRecordService(
            @NonNull TurnRecordRepository repository, @NonNull ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public @NonNull SessionRecordsView load(
            @NonNull String sessionId,
            @NonNull String sessionName,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        List<TurnRecordEntity> rows = repository.findBySessionIdOrderByTimestampAsc(sessionId);
        Map<String, List<SessionRecord>> byAgent = new LinkedHashMap<>();
        for (TurnRecordEntity row : rows) {
            SessionRecord record = decode(row, sessionId);
            if (record != null) {
                byAgent.computeIfAbsent(record.agentId(), ignored -> new ArrayList<>()).add(record);
            }
        }

        List<SessionRecord> annotated = new ArrayList<>();
        for (List<SessionRecord> stream : byAgent.values()) {
            stream.sort(Comparator.comparingInt(SessionRecord::turnNumber));
            annotated.addAll(SessionRecordProjector.project(stream));
        }
        annotated.sort(
                Comparator.comparing(SessionRecord::timestamp)
                        .thenComparing(SessionRecord::agentId)
                        .thenComparingInt(SessionRecord::turnNumber));

        int visible = (int) annotated.stream().filter(SessionRecord::active).count();
        int rewound = annotated.stream().mapToInt(SessionRecord::rewoundRecords).sum();
        List<SessionRecord> records = List.copyOf(annotated);
        return new SessionRecordsView(
                sessionId,
                sessionName,
                rows.size(),
                visible,
                rewound,
                toolResultPresentation,
                ToolUsageProjector.project(records),
                records);
    }

    private SessionRecord decode(@NonNull TurnRecordEntity row, @NonNull String sessionId) {
        try {
            String agentId = row.getAgentId();
            Map<String, Object> payload = deserializePayload(row.getPayload());
            String type = row.getType();
            if ("SYSTEM_PROMPT".equals(type)) {
                type = "AGENT_INIT";
                Map<String, Object> canonical = new LinkedHashMap<>(payload);
                Object content = canonical.remove("content");
                if (content != null) {
                    canonical.putIfAbsent("system_prompt", content);
                }
                payload = canonical;
            } else if ("RECALL".equals(type)) {
                type = "REWIND";
            }
            return new SessionRecord(
                    agentId == null || agentId.isBlank() ? "legacy" : agentId,
                    row.getTurnNumber(),
                    type,
                    payload,
                    row.getTimestamp(),
                    true,
                    0,
                    0);
        } catch (RuntimeException e) {
            log.warn("Skipping unparseable session record {} in {}", row.getId(), sessionId, e);
            return null;
        }
    }

    private @NonNull Map<String, Object> deserializePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, PAYLOAD_TYPE);
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    public record SessionRecordsView(
            @NonNull String sessionId,
            @NonNull String sessionName,
            int rawRecordCount,
            int visibleRecordCount,
            int rewoundRecordCount,
            @NonNull ToolResultPresentationMode toolResultPresentation,
            @NonNull ToolUsageSummary toolUsage,
            @NonNull List<@NonNull SessionRecord> records) {}
}
