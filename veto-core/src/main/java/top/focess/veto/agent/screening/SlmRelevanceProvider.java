package top.focess.veto.agent.screening;

import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * The SLM relevance seam. The real SLM judges relevance from tool-desc + agent thought + args. This
 * default impl returns {@link Relevance#HIGH} — the documented SLM-absent degradation (trust the
 * agent's relevance by default; the deterministic danger floor still protects).
 */
public interface SlmRelevanceProvider {
    Relevance relevance(ToolCall call, ToolDefinition def, String thought);

    /** Degraded: always HIGH. */
    static SlmRelevanceProvider degraded() {
        return new DegradedSlmRelevanceProvider();
    }
}
