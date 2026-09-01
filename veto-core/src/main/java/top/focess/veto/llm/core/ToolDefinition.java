package top.focess.veto.llm.core;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolResultFormat;

/**
 * Represents a tool definition with its schema.
 *
 * @param name the name of the tool
 * @param description a short, one-line description of what the tool does
 * @param inputSchema the JSON schema for the tool's input arguments
 * @param examples concrete args-object usage examples rendered in the prompt catalog. Prompt-side
 *     metadata only; never sent to a provider (the veto manifest is described in the system prompt,
 *     not as native function-calling tools).
 * @param longDescription the deep, sectioned LLM-facing usage doc. The prompt renderer parses its
 *     standard headings and emits them in canonical contract order rather than annotation order.
 *     Prompt-side metadata only; never sent to a provider. Empty for undocumented tools.
 * @param returnExamples illustrative result shapes rendered after the tool's own result contract;
 *     they are not current observations and are not positionally aligned with {@code examples}.
 *     Prompt-side metadata only; never sent to a provider.
 * @param resultFormats explicit wire-visible result shapes rendered before the rest of the tool
 *     contract
 */
public record ToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull Map<String, Object> inputSchema,
        @NonNull List<String> examples,
        @NonNull String longDescription,
        @NonNull List<String> returnExamples,
        @NonNull List<ToolResultFormat> resultFormats) {

    /** Convenience for tools without examples or a long description; delegates with empties. */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema) {
        this(name, description, inputSchema, List.of(), "", List.of(), defaultResultFormats());
    }

    /**
     * Convenience for tools without a long description; delegates with an empty long description.
     */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema,
            @NonNull List<String> examples) {
        this(name, description, inputSchema, examples, "", List.of(), defaultResultFormats());
    }

    /** Convenience for tools without return examples; delegates with an empty list. */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema,
            @NonNull List<String> examples,
            @NonNull String longDescription) {
        this(
                name,
                description,
                inputSchema,
                examples,
                longDescription,
                List.of(),
                defaultResultFormats());
    }

    /** Compatibility constructor with explicit examples but legacy implicit result formats. */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema,
            @NonNull List<String> examples,
            @NonNull String longDescription,
            @NonNull List<String> returnExamples) {
        this(
                name,
                description,
                inputSchema,
                examples,
                longDescription,
                returnExamples,
                defaultResultFormats());
    }

    private static @NonNull List<@NonNull ToolResultFormat> defaultResultFormats() {
        return List.of(ToolResultFormat.JSON, ToolResultFormat.PLAINTEXT);
    }
}
