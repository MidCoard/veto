package top.focess.veto.controller.dto;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.*;
import top.focess.veto.agent.UsageMeasurement;

/** Payload describing one turn of session history, including token usage details. */
public record HistoryTurnResponse(
        int turnNumber,
        @NonNull String type,
        @NonNull Map<String, Object> payload,
        @NonNull String timestamp,
        Long tokenCount,
        Long usedTokens,
        String tokenCountSource,
        @NonNull List<UsageMeasurement> llmUsage)
        implements RestResponse {}
