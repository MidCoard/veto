package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/** Degraded SLM relevance provider — always returns HIGH (SLM absent). */
public class DegradedSlmRelevanceProvider implements SlmRelevanceProvider {
    @Override
    public @NonNull Relevance relevance(
            @NonNull ToolCall call, @NonNull ToolDefinition def, @NonNull String thought) {
        return Relevance.HIGH;
    }
}
