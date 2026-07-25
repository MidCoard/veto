package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Parses the raw {@code actions} {@link JsonNode} - a flat, ordered array emitted by the agent -
 * into a typed {@link ActionsProgram}. Each action element carries a {@code type} discriminator.
 * The result is validated by {@link ProgramValidator} before guided mode loads it.
 */
public final class ActionsProgramParser {

    private ActionsProgramParser() {}

    /** Parses; throws {@link ProgramValidator.InvalidProgramException} on a malformed program. */
    public static @NonNull ActionsProgram parse(@NonNull JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ProgramValidator.InvalidProgramException("actions must be an array");
        }
        List<Action> actions = new ArrayList<>();
        for (JsonNode a : node) {
            actions.add(parseAction(a));
        }
        return new ActionsProgram(actions);
    }

    private static Action parseAction(JsonNode a) {
        String id = text(a, "id");
        String label = text(a, "label");
        String type = text(a, "type");
        return switch (type) {
            case "tool" ->
                    new ToolAction(
                            id,
                            label,
                            text(a, "tool"),
                            toStringMap(a.get("inputs")),
                            toStringMap(a.get("outputs")));
            case "generate" ->
                    new GenerateAction(
                            id,
                            label,
                            text(a, "prompt"),
                            toStringMap(a.get("inputs")),
                            toStringMap(a.get("outputs")),
                            a.has("thought") && !a.get("thought").isNull()
                                    ? a.get("thought").asBoolean()
                                    : null,
                            nullableText(a, "model_tier"),
                            a.has("temperature") && a.get("temperature").isNumber()
                                    ? a.get("temperature").asDouble()
                                    : null);
            case "goto" -> new GotoAction(id, label, a.get("index").asInt());
            case "conditional_goto" ->
                    new ConditionalGotoAction(
                            id,
                            label,
                            parseCheck(a.get("check")),
                            a.get("true_goto").asInt(),
                            a.has("false_goto") && !a.get("false_goto").isNull()
                                    ? a.get("false_goto").asInt()
                                    : null);
            case "STOP" -> new StopAction(id, label, nullableText(a, "result_binding"));
            default ->
                    throw new ProgramValidator.InvalidProgramException(
                            "unknown action type: " + type);
        };
    }

    private static Check parseCheck(JsonNode c) {
        if (c == null || !c.has("kind")) {
            throw new ProgramValidator.InvalidProgramException("check missing 'kind'");
        }
        return switch (c.get("kind").asText()) {
            case "equals" -> new Check.Equals(text(c, "var"), text(c, "value"));
            case "not_equals" -> new Check.NotEquals(text(c, "var"), text(c, "value"));
            case "contains" -> new Check.Contains(text(c, "var"), text(c, "substring"));
            case "matches" -> new Check.Matches(text(c, "var"), text(c, "regex"));
            case "empty" -> new Check.Empty(text(c, "var"));
            case "not_empty" -> new Check.NotEmpty(text(c, "var"));
            case "numeric" -> new Check.Numeric(text(c, "var"), text(c, "op"), text(c, "value"));
            case "exit_ok" -> new Check.ExitOk(text(c, "step_id"));
            case "llm" -> new Check.Llm(text(c, "prompt"), text(c, "var"));
            default ->
                    throw new ProgramValidator.InvalidProgramException(
                            "unknown check kind: " + c.get("kind"));
        };
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return "";
        }
        return n.get(field).asText();
    }

    private static String nullableText(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asText();
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        node.fields()
                .forEachRemaining(
                        e ->
                                map.put(
                                        e.getKey(),
                                        e.getValue().isNull() ? null : e.getValue().asText()));
        return map;
    }
}
