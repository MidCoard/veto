package top.focess.veto.api.agent;

import org.jspecify.annotations.NonNull;

/** Framed observation and success flag emitted after invocation. */
public record ToolResultEvent(@NonNull String body, boolean success) {}
