package top.focess.veto.builtin.memory;

import org.jspecify.annotations.NonNull;

/** A {@link Memory} paired with its similarity score. */
public record ScoredMemory(@NonNull Memory memory, float score) {}
