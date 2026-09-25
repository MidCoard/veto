package top.focess.veto.api.agent;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Portable tool name and arguments emitted when an agent requests a tool call, immediately before
 * invocation.
 *
 * @param toolName requested tool name
 * @param args decoded argument object associated with the event
 */
public record ToolCallEvent(@NonNull String toolName, @NonNull Map<String, Object> args) {}
