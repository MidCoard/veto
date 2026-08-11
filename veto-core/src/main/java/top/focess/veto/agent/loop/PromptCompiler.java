package top.focess.veto.agent.loop;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ChatMessage;

/**
 * Assembles each outgoing LLM payload from the agent's turn history, persona, and resolved tool
 * manifest. Called once per loop cycle before the {@code UniformLLMCaller} dispatches.
 *
 * <p>Three responsibilities:
 *
 * <ol>
 *   <li><b>System message</b> - compiled ("linked") by substituting dynamic blocks into the
 *       template at {@code default-system-prompt.md}. Blocks: {@code {{LAW}}} (VETO.md, resolved
 *       per-root + cross-root), {@code {{IDENTITY}}} (persona name+description, or a caller base),
 *       {@code {{ROLE}}} (STANDALONE/LEADER/MATE - drives the tool set), {@code {{WORKSPACE}}}
 *       (session roots + path mode), {@code {{ENVIRONMENT}}} (host OS/arch + no-shell run_command
 *       semantics), {@code {{TOOLS}}} (role-scoped catalog, from the SAME flat tools that build
 *       {@code tools[]}), {@code {{BOUNDARIES}}} (deployer-policy "not-do" fence), {@code
 *       {{SKILLS}}} (name+desc catalog). See {@link PromptTemplate} + {@link PromptBlocks}.
 *   <li><b>messages[]</b> - raw history newest->oldest, role-mapped per, REWIND-resolved per ,
 *       token-budgeted with pair-safe truncation (a tool message is never kept without its
 *       preceding assistant tool_call message; system never trimmed).
 *   <li><b>tools[]</b> + <b>response_schema</b> - via the {@link CapabilityTranslator} (flat tools
 *       + the per-turn {@code veto_pulse} schema variant).
 * </ol>
 */
@Component
public class PromptCompiler {

    private final @NonNull CapabilityTranslator translator;
    private final @NonNull SystemPromptResolver systemPromptResolver;
    private final com.fasterxml.jackson.databind.@NonNull ObjectMapper objectMapper;

    @Value("${veto.context.max_input_tokens:32000}")
    private int maxInputTokens;

    @Value("${veto.context.context_fill_ratio:0.9}")
    private double contextFillRatio;

    @Value("${veto.security.deployer-policy:FULL_ACCESS}")
    private @NonNull String deployerPolicyRaw;

    private @NonNull DeployerPolicy deployerPolicy = DeployerPolicy.FULL_ACCESS;

    public
    @NonNull
    PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull SystemPromptResolver systemPromptResolver,
            com.fasterxml.jackson.databind.@NonNull ObjectMapper objectMapper) {
        this.translator = translator;
        this.systemPromptResolver = systemPromptResolver;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initDeployerPolicy() {
        this.deployerPolicy = DeployerPolicy.parse(deployerPolicyRaw);
    }

    /**
     * Compiles the payload for one loop cycle.
     *
     * @param persona the agent's identity + resolved manifest + registered skills
     * @param sessionWorkspace the per-session workspace (the session's actual roots, from the
     *     Gateway). Mounted into the system prompt and used to resolve VETO.md (The Law) so the
     *     prompt reflects the session's real roots, not the default bean workspace.
     * @param systemPromptBase optional identity override (e.g. the Mate base from {@code
     *     veto.group.mate.system-prompt-base}); null/blank -> the persona name+description is used.
     *     Role/tools/boundaries are persona-driven.
     * @param history the raw, append-only turn history (oldest->newest)
     * @param guidedSwitch whether this is the guided-switch turn (emits {@code actions})
     */
    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            @Nullable String systemPromptBase,
            @Nullable List<TurnRecord> history,
            boolean guidedSwitch,
            double correctionFactor) {

        List<top.focess.veto.llm.core.ToolDefinition> flatTools =
                translator.translateTools(List.copyOf(persona.whitelistedTools()));
        String systemMessage =
                buildSystemMessage(persona, sessionWorkspace, systemPromptBase, flatTools);
        List<ChatMessage> conversation = resolveRewinds(history);
        List<ChatMessage> budgeted = fitBudget(systemMessage, conversation, correctionFactor);

        var responseSchema = translator.vetoResponseSchema(guidedSwitch);

        int trimmed = conversation.size() - budgeted.size();
        long estimate = Math.round(ceilChars(systemMessage.length()) * correctionFactor);
        for (ChatMessage msg : budgeted) {
            estimate +=
                    Math.round(
                            ceilChars(msg.content() == null ? 0 : msg.content().length())
                                    * correctionFactor);
        }
        return new CompiledPrompt(
                systemMessage, budgeted, flatTools, responseSchema, trimmed, estimate);
    }

    // ── System message (compile/link) ───────────────────────────────────────────

    private @NonNull String buildSystemMessage(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            @Nullable String base,
            @NonNull List<top.focess.veto.llm.core.ToolDefinition> flatTools) {
        String law = sessionWorkspace.vetoMdResolver().resolve();
        // A caller-supplied base (e.g. veto.group.mate.system-prompt-base) overrides the persona
        // identity line; role, tools, boundaries, skills, and the response format are all
        // persona/config-driven via the template markers (see PromptBlocks).
        String identity =
                (base != null && !base.isBlank())
                        ? base.strip()
                        : PromptBlocks.identity(persona.name(), persona.description());
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("LAW", PromptBlocks.law(law));
        blocks.put("IDENTITY", identity);
        blocks.put("ROLE", PromptBlocks.role(persona.role()));
        blocks.put("WORKSPACE", PromptBlocks.workspace(sessionWorkspace));
        blocks.put("ENVIRONMENT", PromptBlocks.environment());
        blocks.put("TOOLS", PromptBlocks.tools(flatTools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(deployerPolicy));
        blocks.put("SKILLS", PromptBlocks.skills(persona.registeredSkills()));
        return PromptTemplate.render(systemPromptResolver.defaultPrompt(), blocks);
    }

    // ── REWIND resolution ────────────────────────────────────────────

    /**
     * Walks history ascending, applying REWIND suffix-drops; returns the effective compiled list.
     */
    private @NonNull List<ChatMessage> resolveRewinds(@Nullable List<TurnRecord> history) {
        List<ChatMessage> compiled = new ArrayList<>();
        if (history == null) {
            return compiled;
        }
        // Pending thought from an ASSISTANT_THOUGHT turn - merged into the next TOOL_CALL's
        // assistant message (as content + reasoningContent) so the model sees its thought and
        // tool call as a single assistant turn, matching the standard tool-calling format.
        String pendingThought = null;
        String pendingReasoning = null;
        for (TurnRecord turn : history) {
            if (turn.type() == TurnType.REWIND) {
                int fromIndex = ((Number) turn.payload().get("from_index")).intValue();
                truncate(compiled, fromIndex);
                pendingThought = null;
                pendingReasoning = null;
                continue;
            }
            if (turn.type() == TurnType.RECALL) {
                int fromIndex = ((Number) turn.payload().get("from_index")).intValue();
                truncate(compiled, fromIndex);
                compiled.add(ChatMessage.user(str(turn.payload(), "content")));
                pendingThought = null;
                pendingReasoning = null;
                continue;
            }
            if (turn.type() == TurnType.ASSISTANT_THOUGHT) {
                // Buffer the thought + reasoning_content; merge into the next TOOL_CALL or
                // ASSISTANT_RESPONSE. Not emitted as a standalone message (saves tokens and
                // avoids DeepSeek's reasoning_content echo requirement on thought-only messages).
                pendingThought = str(turn.payload(), "response");
                pendingReasoning = str(turn.payload(), "reasoning_content");
                if (pendingReasoning.isBlank()) {
                    pendingReasoning = null;
                }
                continue;
            }
            ChatMessage msg = mapRole(turn, pendingThought, pendingReasoning);
            if (msg != null) {
                compiled.add(msg);
            }
            pendingThought = null;
            pendingReasoning = null;
        }
        // A trailing thought with no following turn (e.g. thought + STOP) - emit as assistant.
        if (pendingThought != null && !pendingThought.isBlank()) {
            compiled.add(ChatMessage.assistant(pendingThought));
        }
        return compiled;
    }

    private static void truncate(@NonNull List<ChatMessage> compiled, int fromIndex) {
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        while (compiled.size() > fromIndex) {
            compiled.remove(compiled.size() - 1);
        }
    }

    /**
     * Role mapping. ASSISTANT_THOUGHT is handled by the caller (resolveRewinds buffers it and
     * merges into the next TOOL_CALL or ASSISTANT_RESPONSE). The pending thought/reasoning are
     * passed so the merged assistant message carries both content and reasoning_content.
     */
    private @Nullable ChatMessage mapRole(
            @NonNull TurnRecord turn,
            @Nullable String pendingThought,
            @Nullable String pendingReasoning) {
        String thoughtContent = pendingThought != null ? pendingThought : "";
        return switch (turn.type()) {
            case USER_PROMPT -> ChatMessage.user(str(turn.payload(), "content"));
            case USER_INTERRUPT ->
                    ChatMessage.user("[User feedback]: " + str(turn.payload(), "feedback"));
            case ASSISTANT_THOUGHT -> null; // handled by resolveRewinds
            case ASSISTANT_RESPONSE -> ChatMessage.assistant(str(turn.payload(), "content"));
            case TOOL_CALL -> {
                // Native tool-call merged with the pending thought: content=thought,
                // tool_calls=[...], reasoningContent=reasoning. This is the standard tool-calling
                // format (assistant message with both text and tool_calls) and satisfies DeepSeek
                // thinking mode's reasoning_content echo requirement.
                String callId = str(turn.payload(), "call_id");
                String toolName = str(turn.payload(), "tool_name");
                String toolArgs = serializeArgs(turn.payload().get("args"));
                yield ChatMessage.assistantToolCall(
                        callId, toolName, toolArgs, thoughtContent, pendingReasoning);
            }
            case TOOL_RESPONSE -> {
                // Raw tool output linked by callId. No text framing - the provider SDK renders
                // it as a native tool_result with tool_call_id. Synthetic observations (llm_error,
                // guided_program_rejected, etc.) have a null/empty call_id - these are system-
                // injected feedback, NOT tool results, so they must be user messages (a tool
                // message without a preceding tool_calls assistant message is rejected by the API).
                String callId = str(turn.payload(), "call_id");
                String content = str(turn.payload(), "content");
                if (callId.isBlank()) {
                    yield ChatMessage.user(content);
                }
                yield ChatMessage.toolResult(callId, content);
            }
            case AGENT_INIT -> null;
            case COMPACTION_SUMMARY -> ChatMessage.user(str(turn.payload(), "content"));
            case REWIND -> null;
            case RECALL -> null;
        };
    }

    /** Serializes the args map to a JSON string for the toolCall arguments field. */
    private @NonNull String serializeArgs(@Nullable Object args) {
        if (args == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return args.toString();
        }
    }

    private static @NonNull String str(@Nullable Map<String, Object> payload, @NonNull String key) {
        if (payload == null) {
            return "";
        }
        Object v = payload.get(key);
        return v == null ? "" : v.toString();
    }

    // ── Token budget ──────────────────────────────────────────────────

    /**
     * Walks newest->oldest, keeping turns until the budget is exceeded (system never trimmed).
     *
     * <p><b>Pair-safe:</b> a {@code tool} role message is never kept without its preceding {@code
     * assistant} message (the tool-call that produced it). When the truncator encounters a tool
     * message, it includes the preceding assistant message as part of the same unit - both are kept
     * or both are dropped. This prevents a malformed conversation where a tool result appears with
     * no associated tool call, which most provider APIs reject.
     */
    private @NonNull List<ChatMessage> fitBudget(
            @NonNull String systemMessage,
            @NonNull List<ChatMessage> conversation,
            double correctionFactor) {
        long budget = (long) (maxInputTokens * contextFillRatio);
        long estimate = Math.round(ceilChars(systemMessage.length()) * correctionFactor);
        List<ChatMessage> kept = new ArrayList<>();
        int i = conversation.size() - 1;
        while (i >= 0) {
            ChatMessage msg = conversation.get(i);
            // Pair-safety: a tool message must not be kept without its preceding assistant
            // tool_call message. Treat the (assistant, tool) pair as a single budget unit.
            if ("tool".equals(msg.role())
                    && i > 0
                    && "assistant".equals(conversation.get(i - 1).role())) {
                ChatMessage paired = conversation.get(i - 1);
                long pairEstimate =
                        Math.round(
                                (ceilChars(contentLen(msg)) + ceilChars(contentLen(paired)))
                                        * correctionFactor);
                if (estimate + pairEstimate > budget && !kept.isEmpty()) {
                    break; // neither fits - stop
                }
                kept.add(0, msg); // tool first (so it ends up after assistant)
                kept.add(0, paired); // assistant before tool
                estimate += pairEstimate;
                i -= 2; // consumed both messages
                continue;
            }
            // Non-tool message, or a tool message with no preceding assistant (synthetic
            // observation from llm_error / guided_program_rejected - safe to keep alone).
            long turnEstimate = Math.round(ceilChars(contentLen(msg)) * correctionFactor);
            if (estimate + turnEstimate > budget && !kept.isEmpty()) {
                break;
            }
            kept.add(0, msg);
            estimate += turnEstimate;
            i--;
        }
        return kept;
    }

    private static int contentLen(@NonNull ChatMessage msg) {
        return msg.content() == null ? 0 : msg.content().length();
    }

    private static long ceilChars(int chars) {
        return (long) Math.ceil(chars / 3.0);
    }
}
