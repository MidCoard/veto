package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * The SLM relevance seam. The real SLM judges relevance from tool-desc + agent thought + args. This
 * default impl returns {@link Relevance#HIGH} — the documented SLM-absent degradation (trust the
 * agent's relevance by default; the deterministic danger floor still protects).
 */
public interface SlmRelevanceProvider {
    @NonNull SlmScreening screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String activeTask, String thought);

    default @NonNull SlmScreening screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return screen(call, def, null, thought);
    }

    default @NonNull Relevance relevance(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return screen(call, def, thought).relevance();
    }

    /** Degraded: always HIGH. */
    static @NonNull SlmRelevanceProvider degraded() {
        return new DegradedSlmRelevanceProvider();
    }
}
