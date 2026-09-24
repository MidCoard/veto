package top.focess.veto.api.agent;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Tool name and arguments emitted immediately before invocation. */
public record ToolCallEvent(@NonNull String toolName, @NonNull Map<String, Object> args) {}
