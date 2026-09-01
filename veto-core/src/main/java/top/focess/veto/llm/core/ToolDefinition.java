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

    public ToolDefinition {
        inputSchema = Map.copyOf(inputSchema);
        examples = List.copyOf(examples);
        returnExamples = List.copyOf(returnExamples);
        resultFormats = List.copyOf(resultFormats);
    }
}
