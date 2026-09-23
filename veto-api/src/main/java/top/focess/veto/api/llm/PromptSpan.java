package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

public record PromptSpan(@NonNull String source, int line, int column, int start, int end) {}
