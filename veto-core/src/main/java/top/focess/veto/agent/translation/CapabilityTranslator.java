package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Translates the unified capability manifest into provider-facing forms. The translator owns the
 * implementation; the {@code PromptCompiler} calls it. The translator owns two responsibilities:
 *
 * <ol>
 *   <li>{@link #translateTools} - manifest {@link ToolDefinition} (sealed) -> flat {@code
 *       llm.core.ToolDefinition} (name / description / inputSchema) for the {@code
 *       VetoRequest.tools} list the providers consume.
 *   <li>{@link #vetoResponseSchema} - the per-turn {@code veto_pulse} response schema variant that
 *       constrains the model to emit a {@link VetoResponse}. The variant is governed by the guided
 *       state (autonomous vs guided-switch). {@code thought} is always optional.
 * </ol>
 */
public interface CapabilityTranslator {

    /**
     * Translates the whitelisted manifest tools into the flat, provider-facing {@link
     * top.focess.veto.llm.core.ToolDefinition} list carried by {@code VetoRequest.tools}.
     */
    @NonNull List<top.focess.veto.llm.core.ToolDefinition> translateTools(
            List<ToolDefinition> manifest);

    /**
     * Builds the per-turn {@code veto_pulse} response schema that constrains the model to a {@link
     * top.focess.veto.llm.core.VetoResponse}.
     *
     * @param guidedSwitch whether this is the guided-switch turn (emits {@code actions} + {@code
     *     features.guided=true}; {@code calls} forbidden) vs an autonomous turn ({@code calls}
     *     allowed, {@code actions} forbidden). {@code thought} is always an optional property.
     */
    @NonNull JsonNode vetoResponseSchema(boolean guidedSwitch);

    /**
     * Builds the response schema with the exact role-scoped tool catalog for this turn. Autonomous
     * schemas use these names to constrain {@code calls[].tool_name}; guided schemas have no calls.
     */
    default @NonNull JsonNode vetoResponseSchema(
            boolean guidedSwitch, @NonNull List<top.focess.veto.llm.core.ToolDefinition> tools) {
        return vetoResponseSchema(guidedSwitch);
    }
}
