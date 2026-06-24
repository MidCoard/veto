package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import top.focess.veto.agent.mcp.ToolDefinition;

/**
 * Translates the unified capability manifest into provider-facing forms. Part 5 owns the
 * implementation; Part 1's {@code PromptCompiler} calls it. Per the provider schema translator and
 * Part 1's prompt compiler , the translator owns two responsibilities:
 *
 * <ol>
 *   <li>{@link #translateTools} — manifest {@link ToolDefinition} (sealed) → flat {@code
 *       llm.core.ToolDefinition} (name / description / inputSchema) for the {@code
 *       VetoRequest.tools} list the providers consume.
 *   <li>{@link #vetoResponseSchema} — the per-turn {@code veto_pulse} response schema variant that
 *       constrains the model to emit a {@link top.focess.veto.llm.core.VetoResponse}. The variant
 *       is governed by the effective thought flag and guided state (the four-cell matrix in {@code
 *       prompt_react_syntax.md} ).
 * </ol>
 *
 * <p><b>Phase-0 contract note:</b> the names this {@code ProviderSchemaTranslator<T>} with a single
 * {@code T translate(ToolDefinition)} method and a stale {@code McpToolDefinition} import (a typo
 * for {@link ToolDefinition}). Part 1's {@code PromptCompiler} calls it {@code
 * CapabilityTranslator}. The two-method shape here consolidates the tools + response-schema
 * responsibilities per the coordinator's decision (translator owns both), superseding the old
 * single-{@code call}-shape {@code SchemaNormalizerService}. This interface is shared/read-only: do
 * not modify without coordinator approval; if insufficient, stop and report.
 */
public interface CapabilityTranslator {

    /**
     * Translates the whitelisted manifest tools into the flat, provider-facing {@link
     * top.focess.veto.llm.core.ToolDefinition} list carried by {@code VetoRequest.tools}.
     */
    List<top.focess.veto.llm.core.ToolDefinition> translateTools(List<ToolDefinition> manifest);

    /**
     * Builds the per-turn {@code veto_pulse} response schema that constrains the model to a {@link
     * top.focess.veto.llm.core.VetoResponse}.
     *
     * @param thoughtRequired whether the effective thought flag is ON (thought present &amp;
     *     required) or OFF (thought forbidden — removed from properties).
     * @param guidedSwitch whether this is the guided-switch turn (emits {@code actionsProgram} +
     *     {@code features.guided=true}; {@code calls} forbidden) vs an autonomous turn.
     */
    JsonNode vetoResponseSchema(boolean thoughtRequired, boolean guidedSwitch);
}
