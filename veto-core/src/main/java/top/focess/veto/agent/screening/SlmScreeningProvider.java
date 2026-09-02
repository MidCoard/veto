package top.focess.veto.agent.screening;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * Optional semantic relevance-and-danger screening supplied by a local model. An empty result means
 * that no usable model judgment was produced; it must not be represented as a fabricated HIGH
 * relevance or SAFE danger result.
 */
public interface SlmScreeningProvider {
    @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String activeTask, String thought);

    default @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return screen(call, def, null, thought);
    }

    static @NonNull SlmScreeningProvider unavailable() {
        return (call, def, activeTask, thought) -> Optional.empty();
    }
}
