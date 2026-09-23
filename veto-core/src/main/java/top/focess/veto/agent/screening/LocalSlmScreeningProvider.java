package top.focess.veto.agent.screening;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.LlamaCppBridge;

/** Local llama.cpp relevance-and-danger screening provider. */
@Component
public class LocalSlmScreeningProvider implements SlmScreeningProvider {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.screening.LocalSlmScreeningProvider");
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private final @NonNull LlamaCppBridge bridge;

    public LocalSlmScreeningProvider(@NonNull LlamaCppBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public @NonNull Optional<SlmScreening> screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought,
            String executionContext) {
        if (!bridge.isAvailable()) {
            return Optional.empty();
        }
        String prompt = buildPrompt(call, def, activeTask, thought, executionContext);
        try {
            String response = bridge.infer(prompt, "veto-screening").get(10, TimeUnit.SECONDS);
            if (response == null) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(response);
            if (root != null && root.isObject()) {
                Relevance relevance = parseRelevance(root.path("relevance").asText());
                Danger danger = parseDanger(root.path("danger").asText());
                if (relevance != null && danger != null) {
                    String reason = root.path("reason").asText("local SLM judgment");
                    return Optional.of(new SlmScreening(relevance, danger, reason));
                }
            }
            log.debug("LocalSlmScreeningProvider produced an unparseable response: '{}'", response);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("LocalSlmScreeningProvider inference failed: {}", safe(e.getMessage()));
            return Optional.empty();
        }
    }

    private static @NonNull String buildPrompt(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought,
            String executionContext) {
        return PromptCompiler.compileText(
                "screening",
                Map.of(
                        "candidate",
                        Map.of(
                                "active_user_task", activeTask == null ? "" : activeTask,
                                "agent_thought", thought == null ? "" : thought,
                                "execution_context",
                                        executionContext == null ? "" : executionContext,
                                "tool",
                                        Map.of(
                                                "name", call.toolName(),
                                                "description", def.description(),
                                                "capability", def.capability(),
                                                "default_danger", def.defaultDanger(),
                                                "arguments", call.args()))));
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

    private static @NonNull String safe(String value) {
        return safe(value, 200);
    }

    private static @NonNull String safe(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
