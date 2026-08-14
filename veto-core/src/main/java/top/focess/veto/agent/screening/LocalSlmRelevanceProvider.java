package top.focess.veto.agent.screening;

import static top.focess.veto.util.LogValues.safe;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Local-SLM-backed {@link SlmRelevanceProvider} (Part 3.2 BETA). Asks the running llama.cpp
 * subprocess whether the agent's emitted call is plausibly in service of the task (HIGH / MEDIUM /
 * LOW). Falls back to {@link Relevance#HIGH} when the SLM is unavailable — the LLD's documented
 * degradation: trust the agent's relevance by default; the deterministic danger floor still
 * protects.
 *
 * <p>The prompt asks the SLM to emit a single token from the set {HIGH, MEDIUM, LOW}; the bridge
 * runs with a GBNF grammar-constrained decoder so the response is always one of those three tokens.
 * This implementation parses the response leniently (any string containing {@code HIGH}, {@code
 * MEDIUM}, or {@code LOW} wins; otherwise the fallback).
 */
public class LocalSlmRelevanceProvider implements SlmRelevanceProvider {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.screening.LocalSlmRelevanceProvider");

    private final @NonNull LlamaCppBridge bridge;

    public LocalSlmRelevanceProvider(@NonNull LlamaCppBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public @NonNull Relevance relevance(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        if (!bridge.isAvailable()) {
            return Relevance.HIGH;
        }
        String prompt = buildPrompt(call, def, thought);
        try {
            String response = bridge.infer(prompt, "veto-relevance").get();
            if (response == null) {
                return Relevance.HIGH;
            }
            String upper = response.toUpperCase();
            if (upper.contains("LOW")) {
                return Relevance.LOW;
            }
            if (upper.contains("MEDIUM")) {
                return Relevance.MEDIUM;
            }
            if (upper.contains("HIGH")) {
                return Relevance.HIGH;
            }
            log.debug(
                    "LocalSlmRelevanceProvider: unparseable response '{}', defaulting HIGH",
                    response);
            return Relevance.HIGH;
        } catch (Exception e) {
            log.warn(
                    "LocalSlmRelevanceProvider: inference failed, defaulting HIGH: {}",
                    safe(e.getMessage()));
            return Relevance.HIGH;
        }
    }

    private static @NonNull String buildPrompt(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return "Given the agent's thought: \""
                + safe(thought)
                + "\"\nAnd the tool call: "
                + call.toolName()
                + "("
                + call.args()
                + ")\nIs this call plausibly in service of the agent's stated task? Reply with one of: HIGH MEDIUM LOW\n";
    }

    private static @NonNull String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
