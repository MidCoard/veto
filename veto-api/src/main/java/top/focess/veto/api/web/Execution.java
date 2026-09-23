package top.focess.veto.api.web;

import org.jspecify.annotations.NonNull;

public record Execution(
        @NonNull String id,
        @NonNull String model,
        long durationMs,
        int modelCalls,
        long promptTokens,
        long completionTokens) {}
