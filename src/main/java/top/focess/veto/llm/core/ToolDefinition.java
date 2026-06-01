package top.focess.veto.llm.core;

import java.util.Map;

/**
 * Represents a tool definition with its schema.
 *
 * @param name the name of the tool
 * @param description a description of what the tool does
 * @param inputSchema the JSON schema for the tool's input arguments
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}
