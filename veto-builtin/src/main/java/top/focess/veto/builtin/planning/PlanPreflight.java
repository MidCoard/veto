package top.focess.veto.builtin.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.ControlSubmission;

/** Whole-program feature preflight. No step acquires execution authority here. */
public final class PlanPreflight {
    private PlanPreflight() {}

    public static @Nullable String validate(
            @NonNull ActionsProgram program,
            @NonNull ControlHost host,
            @NonNull ObjectMapper mapper) {
        var tools = host.tools();
        String answer =
                tools.stream()
                        .filter(
                                tool ->
                                        tool.control() == ControlSubmission.Kind.FINISH
                                                && (("top.focess.builtin".equals(tool.pluginId())
                                                                && "answer_with_citations"
                                                                        .equals(tool.localId()))
                                                        || (tool.pluginId() == null
                                                                && "answer_with_citations"
                                                                        .equals(
                                                                                tool.definition()
                                                                                        .name()))))
                        .map(tool -> tool.definition().name())
                        .findFirst()
                        .orElse(null);
        for (var action : program.actions()) {
            if (action instanceof GenerateAction generated
                    && generated.responseMode() == GenerateAction.ResponseMode.CITATIONS
                    && answer == null)
                throw new IllegalArgumentException(
                        "CITATIONS generation requires an available answer submission tool");
            if (!(action instanceof ToolAction tool)) continue;
            var target =
                    tools.stream()
                            .filter(candidate -> candidate.definition().name().equals(tool.tool()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Tool is not available in this role: "
                                                            + tool.tool()));
            if (target.control() != null)
                throw new IllegalArgumentException(
                        "Response submission tools cannot be nested as plan tool steps; use generate for a cited answer and STOP to finish");
            Set<String> deferred = new HashSet<>();
            JsonNode values = prepare(mapper.valueToTree(tool.inputs()), "", deferred);
            host.validateInputs(tool.tool(), values, deferred);
        }
        return answer;
    }

    public static @NonNull JsonNode prepare(
            @NonNull JsonNode value, @NonNull String path, @NonNull Set<String> deferred) {
        if (value.isTextual()) {
            var text = value.asText();
            if (text.startsWith("$$")) return TextNode.valueOf(text.substring(1));
            if (text.startsWith("$")) deferred.add(path);
            return value;
        }
        if (value instanceof ObjectNode object) {
            var result = object.deepCopy();
            object.properties()
                    .forEach(
                            entry ->
                                    result.set(
                                            entry.getKey(),
                                            prepare(
                                                    entry.getValue(),
                                                    path.isEmpty()
                                                            ? entry.getKey()
                                                            : path + "." + entry.getKey(),
                                                    deferred)));
            return result;
        }
        if (value instanceof ArrayNode array) {
            var result = array.deepCopy();
            for (int i = 0; i < array.size(); i++)
                result.set(i, prepare(array.path(i), path + "[" + i + "]", deferred));
            return result;
        }
        return value;
    }
}
