package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * The assembled LLM payload produced by {@link PromptCompiler} each loop cycle ({@code
 * prompt_compiler.md}). The loop combines this with provider/model/credential options to build a
 * {@link top.focess.veto.llm.core.VetoRequest}.
 *
 * @param systemMessage the Layer-1+2+3 system message (always {@code messages[0]}, never trimmed)
 * @param messages the role-mapped, token-budgeted conversation (newest→oldest, pair-safe
 *     truncation)
 * @param tools the flat, provider-translated tool list (full whitelist, every cycle)
 * @param responseSchema the per-turn {@code veto_pulse} schema variant ({@code null} → provider
 *     default)
 * @param trimmedTurns how many oldest turns were dropped to fit the token budget
 */
public record CompiledPrompt(
        String systemMessage,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        JsonNode responseSchema,
        int trimmedTurns) {}
