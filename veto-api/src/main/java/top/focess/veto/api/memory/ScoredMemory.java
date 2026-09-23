package top.focess.veto.api.memory;

import org.jspecify.annotations.NonNull;

public record ScoredMemory(@NonNull Memory memory, float score) {}
