package top.focess.veto.agent.screening;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.llm.ToolCall;

/**
 * Optional semantic relevance-and-danger screening supplied by a local model. An empty result means
 * that no usable model judgment was produced; it must not be represented as a fabricated HIGH
 * relevance or SAFE danger result.
 */
public interface SlmScreeningProvider {
    /**
     * Produces the model's relevance-and-danger judgment for a candidate call, or {@link
     * Optional#empty()} when no usable judgment was produced.
     */
    @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought,
            String executionContext);

    /** Convenience overload with no active task or execution context. */
    default @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return screen(call, def, null, thought, null);
    }

    /** A provider that never yields a judgment, used when local screening is unavailable. */
    static @NonNull SlmScreeningProvider unavailable() {
        return (call, def, activeTask, thought, executionContext) -> Optional.empty();
    }
}
