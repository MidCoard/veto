package top.focess.veto.controller.dto;

import org.jspecify.annotations.*;

public record HistoryTurnResponse(
        int turnNumber,
        @NonNull String type,
        java.util.@NonNull Map<String, Object> payload,
        @NonNull String timestamp,
        @Nullable Long tokenCount,
        @Nullable Long usedTokens,
        @Nullable String tokenCountSource)
        implements RestResponse {}
