package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standardized response that the Veto Agent Loop expects.
 *
 * @param thought deep reasoning string explaining the plan
 * @param call the tool call to execute, or null if finished
 * @param isFinished whether the agent has completed its task
 */
public record VetoResponse(
        String thought, ToolCall call, @JsonProperty("is_finished") boolean isFinished) {
}
