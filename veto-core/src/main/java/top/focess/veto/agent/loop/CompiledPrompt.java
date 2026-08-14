package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * The assembled LLM payload produced by {@link PromptCompiler} each loop cycle. The loop combines
 * this with provider/model/credential options to build a {@link
 * top.focess.veto.llm.core.VetoRequest}.
 *
 * @param systemMessage the Layer-1+2+3 system message (always {@code messages[0]}, never trimmed)
 * @param messages the role-mapped, token-budgeted conversation, oldest→newest. Pair-safe truncation
 *     plus the {@code PromptCompiler.wellFormed} contract: it opens on a user message, every
 *     tool_result immediately follows its tool_call, and no tool_call dangles unanswered - the
 *     shape every strict provider accepts.
 * @param tools the flat, provider-translated tool list (full whitelist, every cycle)
 * @param responseSchema the per-turn {@code veto_pulse} schema variant ({@code null} → provider
 *     default)
 * @param trimmedTurns how many oldest turns were dropped to fit the token budget
 */
public record CompiledPrompt(
        @NonNull String systemMessage,
        @NonNull List<ChatMessage> messages,
        @NonNull List<ToolDefinition> tools,
        JsonNode responseSchema,
        int trimmedTurns,
        long estimatedTokens) {}
