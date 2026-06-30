package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

public record ModelBinding(
        Provider provider,
        @NonNull String modelId,
        double defaultTemperature,
        int maxOutputTokens) {}
