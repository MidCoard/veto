package top.focess.veto.agent.screening;

import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/** Degraded SLM relevance provider — always returns HIGH (SLM absent). */
public class DegradedSlmRelevanceProvider implements SlmRelevanceProvider {
    @Override
    public Relevance relevance(ToolCall call, ToolDefinition def, String thought) {
        return Relevance.HIGH;
    }
}
