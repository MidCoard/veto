package top.focess.veto.agent.screening;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
@Component
public class LocalSlmRelevanceProvider implements SlmRelevanceProvider {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.screening.LocalSlmRelevanceProvider");
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private final @NonNull LlamaCppBridge bridge;

    public LocalSlmRelevanceProvider(@NonNull LlamaCppBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public @NonNull SlmScreening screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought) {
        if (!bridge.isAvailable()) {
            return SlmScreening.degraded();
        }
        String prompt = buildPrompt(call, def, activeTask, thought);
        try {
            String response = bridge.infer(prompt, "veto-screening").get(10, TimeUnit.SECONDS);
            if (response == null) {
                return SlmScreening.degraded();
            }
            JsonNode root = MAPPER.readTree(response);
            if (root != null && root.isObject()) {
                Relevance relevance = parseRelevance(root.path("relevance").asText());
                Danger danger = parseDanger(root.path("danger").asText());
                if (relevance != null && danger != null) {
                    String reason = root.path("reason").asText("local SLM judgment");
                    return new SlmScreening(relevance, danger, reason);
                }
            }
            log.debug(
                    "LocalSlmRelevanceProvider: unparseable response '{}', defaulting HIGH",
                    response);
            return SlmScreening.degraded();
        } catch (Exception e) {
            log.warn(
                    "LocalSlmRelevanceProvider: inference failed, defaulting HIGH: {}",
                    safe(e.getMessage()));
            return SlmScreening.degraded();
        }
    }

    private static @NonNull String buildPrompt(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought) {
        return "Active user task: \""
                + safe(activeTask)
                + "\"\nGiven the agent's thought: \""
                + safe(thought)
                + "\"\nTool description: "
                + def.description()
                + "\nTool risk category: "
                + def.risk()
                + "\nTool call: "
                + call.toolName()
                + "("
                + call.args()
                + ")\nJudge whether the call is relevant to the active task and whether its intent"
                + " adds semantic danger. Reply only as JSON with fields in this order: relevance"
                + " HIGH/MEDIUM/LOW, danger SAFE/ELEVATED/DANGEROUS/CRITICAL, and a short"
                + " reason.\n";
    }

    private static Relevance parseRelevance(@NonNull String value) {
        try {
            return Relevance.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Danger parseDanger(@NonNull String value) {
        try {
            return Danger.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @NonNull String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
