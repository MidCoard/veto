package top.focess.veto.model.tier;

public record ModelBinding(
        Provider provider, String modelId, double defaultTemperature, int maxOutputTokens) {}
