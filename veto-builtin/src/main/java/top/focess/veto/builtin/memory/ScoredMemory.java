package top.focess.veto.builtin.memory;

import org.jspecify.annotations.NonNull;

public record ScoredMemory(@NonNull Memory memory, float score) {}
