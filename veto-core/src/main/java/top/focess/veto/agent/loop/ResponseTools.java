package top.focess.veto.agent.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** Explicit native operations for the two structured runtime features. Text is never parsed. */
public final class ResponseTools {
    private ResponseTools() {}

    public static boolean isSubmission(String name) {
        return "submit_guide".equals(name) || "answer_with_citations".equals(name);
    }

    public static @NonNull List<ToolDefinition> add(
            @NonNull List<ToolDefinition> tools, @NonNull JsonNode featureSchema) {
        var mapper = new ObjectMapper();
        var result = new ArrayList<>(tools);
        var properties = featureSchema.path("properties");
        if (properties.has("guide")) {
            result.add(
                    new ToolDefinition(
                            "submit_guide",
                            "Submit a complete guided program for runtime validation and execution. Call this tool alone, without other tools in the same response.",
                            mapper.convertValue(
                                    properties.get("guide"),
                                    new TypeReference<Map<String, Object>>() {}),
                            List.of(),
                            top.focess.veto.agent.tool.ToolDocumentation.empty(),
                            List.of(),
                            List.of()));
        }
        var answer =
                mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        answer.putObject("properties").set("message", properties.get("message"));
        answer.withObject("properties").set("citations", properties.get("citations"));
        answer.putArray("required").add("message").add("citations");
        result.add(
                new ToolDefinition(
                        "answer_with_citations",
                        "Finish with an answer citing exact passages from conversation messages. Supply the answer and its citation declarations. Call this tool alone. For answers without conversation citations, reply directly in text instead.",
                        mapper.convertValue(answer, new TypeReference<Map<String, Object>>() {}),
                        List.of(),
                        top.focess.veto.agent.tool.ToolDocumentation.empty(),
                        List.of(),
                        List.of()));
        return List.copyOf(result);
    }

    public static ToolCall submission(@NonNull VetoResponse response) {
        var calls = response.calls();
        if (calls == null) return null;
        var submissions = calls.stream().filter(c -> isSubmission(c.toolName())).toList();
        if (submissions.isEmpty()) return null;
        if (calls.size() != 1)
            throw new ModelSchemaException(
                    "A response submission must be the only native tool call");
        return submissions.getFirst();
    }

    public static @NonNull VetoResponse decode(
            @NonNull VetoResponse response, @NonNull ToolCall call, @NonNull ObjectMapper mapper) {
        try {
            var args = mapper.valueToTree(call.args());
            if (call.toolName().equals("submit_guide")) {
                if (args.size() != 1
                        || !args.path("actions").isArray()
                        || args.path("actions").isEmpty())
                    throw new IllegalArgumentException(
                            "submit_guide requires a non-empty actions array");
                var program = ActionsProgramParser.parse(args.get("actions"));
                ProgramValidator.validate(program);
                ProgramValidator.validateInputs(program);
                return new VetoResponse(
                        null,
                        null,
                        response.message(),
                        new VetoResponse.Guide(args.get("actions")));
            }
            if (args.size() != 2
                    || !args.path("message").isTextual()
                    || !args.path("citations").isArray())
                throw new IllegalArgumentException(
                        "answer_with_citations requires message and citations");
            List<VetoResponse.Citation> citations =
                    mapper.convertValue(
                            args.get("citations"),
                            new TypeReference<List<VetoResponse.Citation>>() {});
            return new VetoResponse(null, null, args.get("message").asText(), null, citations);
        } catch (IllegalArgumentException | ProgramValidator.InvalidProgramException e) {
            throw new ModelSchemaException(
                    "Invalid " + call.toolName() + " arguments: " + e.getMessage());
        }
    }
}
