package top.focess.veto.sandbox;

import org.jspecify.annotations.NonNull;

/** A grep match returned by {@code grep_search}. */
public record Match(@NonNull String file, int line, @NonNull String text) {}
