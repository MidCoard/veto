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
        if (!node.isArray()) {
            throw new ProgramValidator.InvalidProgramException("actions must be an array");
        }
        List<Action> actions = new ArrayList<>();
        for (JsonNode a : node) {
            actions.add(parseAction(a));
        }
        return new ActionsProgram(actions);
    }

    private static @NonNull Action parseAction(@NonNull JsonNode a) {
        String id = text(a, "id");
        String label = text(a, "label");
        String type = text(a, "type");
        return switch (type) {
            case "tool" ->
                    new ToolAction(
                            id,
                            label,
                            text(a, "tool"),
                            toInputMap(a.get("inputs")),
                            toStringMap(a.get("outputs")));
            case "generate" ->
                    new GenerateAction(
                            id,
                            label,
                            text(a, "prompt"),
                            toInputMap(a.get("inputs")),
                            toStringMap(a.get("outputs")),
                            optionalBoolean(a, "thought"),
                            nullableText(a, "model_tier"),
                            optionalNumber(a, "temperature"));
            case "goto" -> new GotoAction(id, label, integer(a, "index"));
            case "conditional_goto" ->
                    new ConditionalGotoAction(
                            id,
                            label,
                            parseCheck(a.get("check")),
                            integer(a, "true_goto"),
                            a.has("false_goto") && !a.get("false_goto").isNull()
                                    ? integer(a, "false_goto")
                                    : null);
            case "STOP" -> new StopAction(id, label, nullableText(a, "result_binding"));
            default ->
                    throw new ProgramValidator.InvalidProgramException(
                            "unknown action type: " + type);
        };
    }

    private static @NonNull Check parseCheck(JsonNode c) {
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

    private static Boolean optionalBoolean(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean())
            throw new ProgramValidator.InvalidProgramException(field + " must be boolean");
        return value.booleanValue();
    }

    private static Double optionalNumber(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber())
            throw new ProgramValidator.InvalidProgramException(field + " must be numeric");
        return value.doubleValue();
    }

    private static int integer(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new ProgramValidator.InvalidProgramException(field + " must be an integer");
        return value.intValue();
    }

    private static @NonNull String text(JsonNode n, @NonNull String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return "";
        }
        if (!n.get(field).isTextual())
            throw new ProgramValidator.InvalidProgramException(field + " must be a string");
        return n.get(field).asText();
    }

    private static String nullableText(JsonNode n, @NonNull String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        if (!n.get(field).isTextual())
            throw new ProgramValidator.InvalidProgramException(field + " must be a string");
        return n.get(field).asText();
    }

    private static @NonNull Map<String, Object> toInputMap(JsonNode node) {
        if (node == null || !node.isObject())
            throw new ProgramValidator.InvalidProgramException("inputs must be an object");
        Map<String, Object> values = new HashMap<>();
        node.properties().forEach(e -> values.put(e.getKey(), e.getValue().deepCopy()));
        return values;
    }

    private static @NonNull Map<@NonNull String, @NonNull String> toStringMap(JsonNode node) {
        Map<@NonNull String, @NonNull String> map = new HashMap<>();
        if (node == null || !node.isObject())
            throw new ProgramValidator.InvalidProgramException("outputs must be an object");
        node.properties()
                .forEach(
                        e -> {
                            if (!e.getValue().isTextual()) {
                                throw new ProgramValidator.InvalidProgramException(
                                        "action binding '" + e.getKey() + "' must be a string");
                            }
                            map.put(e.getKey(), e.getValue().asText());
                        });
        return map;
    }
}
