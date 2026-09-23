package top.focess.veto.api.web;

import org.jspecify.annotations.NonNull;

public record Evidence(@NonNull String url, @NonNull String section, @NonNull String quote) {}
