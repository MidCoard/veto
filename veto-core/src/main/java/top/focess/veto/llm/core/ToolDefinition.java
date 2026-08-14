package top.focess.veto.llm.core;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Represents a tool definition with its schema.
 *
 * @param name the name of the tool
 * @param description a short, one-line description of what the tool does
 * @param inputSchema the JSON schema for the tool's input arguments
 * @param examples concrete args-object usage examples rendered in the prompt catalog. Prompt-side
 *     metadata only; never sent to a provider (the veto manifest is described in the system prompt,
 *     not as native function-calling tools).
 * @param longDescription the deep, multi-paragraph LLM-facing usage doc rendered verbatim under the
 *     tool's catalog entry (when to use, when not to, behavior, return format, edges). Prompt-side
 *     metadata only; never sent to a provider. Empty for tools without a {@code @ToolDoc}
 *     description.
 * @param returnExamples concrete return-value examples rendered in the prompt catalog (one or two
 *     representative shapes in the tool's declared output kind; NOT positionally aligned with
 *     {@code examples}). Prompt-side metadata only; never sent to a provider.
 */
public record ToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull Map<String, Object> inputSchema,
        @NonNull List<String> examples,
        @NonNull String longDescription,
        @NonNull List<String> returnExamples) {

    /** Convenience for tools without examples or a long description; delegates with empties. */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema) {
        this(name, description, inputSchema, List.of(), "", List.of());
    }

    /**
     * Convenience for tools without a long description; delegates with an empty long description.
     */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema,
            @NonNull List<String> examples) {
        this(name, description, inputSchema, examples, "", List.of());
    }

    /** Convenience for tools without return examples; delegates with an empty list. */
    public ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema,
            @NonNull List<String> examples,
            @NonNull String longDescription) {
        this(name, description, inputSchema, examples, longDescription, List.of());
    }
}
