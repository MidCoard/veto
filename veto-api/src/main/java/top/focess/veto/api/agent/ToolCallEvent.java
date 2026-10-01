package top.focess.veto.api.agent;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolCall;

/**
 * Portable tool name and arguments emitted when an agent requests a tool call, immediately before
 * invocation.
 *
 * @param toolName requested tool name
 * @param args owned immutable JSON argument snapshot associated with the event
 */
public record ToolCallEvent(@NonNull String toolName, @NonNull Map<@NonNull String, Object> args) {
    /** Detaches nested argument containers before notifying output listeners. */
    public ToolCallEvent {
        args = ToolCall.snapshotArguments(args);
    }
}
