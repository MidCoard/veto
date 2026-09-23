package top.focess.veto.api.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/**
 * Represents a tool definition with its schema.
 *
 * @param name the name of the tool
 * @param description a short, one-line description of what the tool does
 * @param inputSchema the JSON schema for the tool's input arguments
 * @param examples concrete args-object usage examples rendered in the prompt catalog. Prompt-side
 *     metadata only; native provider tool declarations contain only name, description and input
 *     schema.
 * @param documentation typed LLM-facing documentation sections. Prompt-side metadata only; never
 *     sent to a provider.
 * @param returnExamples illustrative result shapes rendered after the tool's own result contract;
 *     they are not current observations. Positionally aligned with {@code examples}: entry {@code
 *     i} is the success result of the call shown in {@code examples.get(i)}. Prompt-side metadata
 *     only; never sent to a provider.
 * @param resultFormats explicit wire-visible result shapes rendered before the rest of the tool
 *     contract
 */
public record ToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull Map<String, Object> inputSchema,
        @NonNull List<String> examples,
        @NonNull ToolDocumentation documentation,
        @NonNull List<String> returnExamples,
        @NonNull List<ToolResultFormat> resultFormats) {

    public ToolDefinition {
        inputSchema = ordered(inputSchema);
        examples = List.copyOf(examples);
        returnExamples = List.copyOf(returnExamples);
        resultFormats = List.copyOf(resultFormats);
    }

    /**
     * The wire-facing view actually sent to providers: name, description, and input schema.
     * Prompt-side metadata (examples, documentation, return examples) is excluded, so input-budget
     * accounting measures what the request really carries.
     */
    public static @NonNull Map<String, Object> wireView(@NonNull ToolDefinition tool) {
        return Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "inputSchema", tool.inputSchema());
    }

    private static @NonNull Map<String, Object> ordered(@NonNull Map<?, ?> source) {
        Map<String, Object> result = new TreeMap<>();
        source.forEach(
                (key, value) -> {
                    if (!(key instanceof String name) || value == null)
                        throw new IllegalArgumentException("Invalid schema entry");
                    result.put(name, orderedValue(value));
                });
        return Collections.unmodifiableMap(result);
    }

    private static @NonNull Object orderedValue(@NonNull Object value) {
        if (value instanceof Map<?, ?> map) return ordered(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item == null) throw new IllegalArgumentException("Null schema array entry");
                result.add(orderedValue(item));
            }
            return List.copyOf(result);
        }
        return value;
    }
}
