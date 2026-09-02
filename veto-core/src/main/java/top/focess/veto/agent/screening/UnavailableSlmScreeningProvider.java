package top.focess.veto.agent.screening;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/** Provider used when no local semantic screening model is available. */
public class UnavailableSlmScreeningProvider implements SlmScreeningProvider {
    @Override
    public @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought) {
        return Optional.empty();
    }
}
