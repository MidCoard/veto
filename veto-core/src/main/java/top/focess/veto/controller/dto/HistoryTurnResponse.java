package top.focess.veto.controller.dto;

import org.jspecify.annotations.*;

public record HistoryTurnResponse(
        int turnNumber,
        @NonNull String type,
        java.util.@NonNull Map<String, Object> payload,
        @NonNull String timestamp,
        Long tokenCount,
        Long usedTokens,
        String tokenCountSource,
        java.util.@NonNull List<top.focess.veto.agent.UsageMeasurement> llmUsage)
        implements RestResponse {}
