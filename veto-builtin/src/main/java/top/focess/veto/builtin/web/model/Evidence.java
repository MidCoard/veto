package top.focess.veto.builtin.web.model;

import org.jspecify.annotations.NonNull;

/** A quoted passage supporting the read answer. */
public record Evidence(@NonNull String url, @NonNull String section, @NonNull String quote) {}
