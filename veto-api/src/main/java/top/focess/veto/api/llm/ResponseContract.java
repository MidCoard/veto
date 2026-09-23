package top.focess.veto.api.llm;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/** The response channel required by one runtime invocation, independently of provider syntax. */
public record ResponseContract(
        @NonNull Mode mode, @NonNull String completionTool, boolean completionOnly) {
    public enum Mode {
        ORDINARY,
        GENERATION,
        PREDICATE,
        COMPLETION
    }

    public ResponseContract {
        if (mode == Mode.COMPLETION && completionTool.isBlank())
            throw new IllegalArgumentException(
                    "A completion contract requires its completion tool");
        if (mode != Mode.COMPLETION && (!completionTool.isEmpty() || completionOnly))
            throw new IllegalArgumentException("Only completion contracts name a completion tool");
    }

    public static @NonNull ResponseContract ordinary() {
        return new ResponseContract(Mode.ORDINARY, "", false);
    }

    public static @NonNull ResponseContract generation() {
        return new ResponseContract(Mode.GENERATION, "", false);
    }

    public static @NonNull ResponseContract predicate() {
        return new ResponseContract(Mode.PREDICATE, "", false);
    }

    public static @NonNull ResponseContract completion(@NonNull String tool, boolean only) {
        return new ResponseContract(Mode.COMPLETION, tool, only);
    }

    /** Data for the shared provider/retry instruction template; names come from this request. */
    public @NonNull Map<String, Object> promptData(@NonNull VetoRequest request) {
        return Map.of(
                "responseMode",
                mode.name(),
                "toolNames",
                request.nativeToolsEnabled()
                        ? request.tools().stream().map(ToolDefinition::name).toList()
                        : List.of(),
                "completionTool",
                completionTool,
                "completionOnly",
                completionOnly);
    }

    /** Used both on decoded provider output and by the loop's test/custom caller boundary. */
    public void validate(@NonNull VetoRequest request, String text, @NonNull List<String> calls) {
        String answer = text == null ? "" : text.strip();
        for (String name : calls) {
            if (!request.nativeToolsEnabled()
                    || request.tools().stream().noneMatch(tool -> tool.name().equals(name)))
                throw new ModelSchemaException("Tool is not available in this turn: " + name);
        }
        switch (mode) {
            case PREDICATE -> {
                if (!calls.isEmpty() || !(answer.equals("true") || answer.equals("false")))
                    throw new ModelSchemaException(
                            "This predicate requires exactly true or false as plain text; no tool calls");
            }
            case COMPLETION -> {
                if (calls.size() != 1 || !answer.isEmpty())
                    throw new ModelSchemaException(
                            "This invocation requires exactly one native tool call and no accompanying text; complete through "
                                    + completionTool);
                if (completionOnly && !calls.getFirst().equals(completionTool))
                    throw new ModelSchemaException("This invocation must call " + completionTool);
            }
            case GENERATION -> {
                boolean citationSubmission =
                        request.nativeToolsEnabled() && !request.tools().isEmpty();
                if (citationSubmission && (calls.size() != 1 || !answer.isEmpty()))
                    throw new ModelSchemaException(
                            "This generation requires exactly one native answer-submission call and no accompanying text");
            }
            case ORDINARY -> {}
        }
    }
}
