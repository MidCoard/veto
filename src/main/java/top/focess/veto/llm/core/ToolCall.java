package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Represents a specific tool call from the LLM.
 *
 * <p>The {@code @JsonProperty} mappings are mandatory: the response schema emitted to providers
 * uses snake_case ({@code tool_name}), so without them Jackson would silently bind {@code null} and
 * the agent action would vanish.
 *
 * @param toolName the name of the tool being called
 * @param args     the arguments for the tool call
 */
public record ToolCall(
        @JsonProperty("tool_name") String toolName, @JsonProperty("args") Map<String, Object> args) {
}
