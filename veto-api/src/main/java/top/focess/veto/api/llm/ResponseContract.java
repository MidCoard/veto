package top.focess.veto.api.llm;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/**
 * The response channel required by one runtime invocation, independently of provider syntax.
 *
 * @param mode validation mode for this request
 * @param completionTool required completion tool name, empty outside completion mode
 * @param completionOnly whether completion mode accepts only the named completion tool
 */
public record ResponseContract(
        @NonNull Mode mode, @NonNull String completionTool, boolean completionOnly) {
    /** The kind of response accepted from a model invocation. */
    public enum Mode {
        /** Ordinary text and available native tools. */
        ORDINARY,
        /** A single generation submission when native control tools are available. */
        GENERATION,
        /** Exactly a plain-text true or false answer. */
        PREDICATE,
        /** Exactly one completion tool call without accompanying text. */
        COMPLETION
    }

    /** Rejects completion-specific fields in other modes and requires a named completion tool. */
    public ResponseContract {
        if (mode == Mode.COMPLETION && completionTool.isBlank())
            throw new IllegalArgumentException(
                    "A completion contract requires its completion tool");
        if (mode != Mode.COMPLETION && (!completionTool.isEmpty() || completionOnly))
            throw new IllegalArgumentException("Only completion contracts name a completion tool");
    }

    /**
     * Creates the ordinary response contract.
     *
     * @return a contract accepting ordinary text and available tools
     */
    public static @NonNull ResponseContract ordinary() {
        return new ResponseContract(Mode.ORDINARY, "", false);
    }

    /**
     * Creates the generation response contract.
     *
     * @return the generation-submission contract
     */
    public static @NonNull ResponseContract generation() {
        return new ResponseContract(Mode.GENERATION, "", false);
    }

    /**
     * Creates the boolean-predicate response contract.
     *
     * @return a contract requiring a plain-text boolean predicate
     */
    public static @NonNull ResponseContract predicate() {
        return new ResponseContract(Mode.PREDICATE, "", false);
    }

    /**
     * Creates a completion-tool contract.
     *
     * @param tool required completion tool name
     * @param only whether all other native tools are disallowed
     * @return completion contract
     */
    public static @NonNull ResponseContract completion(@NonNull String tool, boolean only) {
        return new ResponseContract(Mode.COMPLETION, tool, only);
    }

    /**
     * Returns data for the shared provider/retry instruction template.
     *
     * @param request request whose currently available tool names are included
     * @return immutable instruction data for this contract
     */
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

    /**
     * Validates decoded provider output and custom caller responses against this contract.
     *
     * @param request request defining available native tools
     * @param text decoded assistant text, or {@code null} when absent
     * @param calls decoded native tool names
     * @throws ModelSchemaException if the response violates the mode or calls unavailable tools
     */
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
                boolean nativeSubmission =
                        request.nativeToolsEnabled() && !request.tools().isEmpty();
                if (nativeSubmission && (calls.size() != 1 || !answer.isEmpty()))
                    throw new ModelSchemaException(
                            "This generation requires exactly one native control call and no accompanying text");
            }
            case ORDINARY -> {}
        }
    }
}
