package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/** Degraded SLM relevance provider — always returns HIGH (SLM absent). */
public class DegradedSlmRelevanceProvider implements SlmRelevanceProvider {
    @Override
    public @NonNull SlmScreening screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought) {
        return SlmScreening.degraded();
    }
}
