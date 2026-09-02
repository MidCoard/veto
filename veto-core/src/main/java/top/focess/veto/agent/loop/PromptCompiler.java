package top.focess.veto.agent.loop;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolResultStatus;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.ToolResultPresenter;

/**
 * Assembles each outgoing LLM payload from the agent's turn history, persona, and resolved tool
 * manifest. Called once per loop cycle before the {@code UniformLLMCaller} dispatches.
 *
 * <p>Three responsibilities:
 *
 * <ol>
 *   <li><b>System message</b> - compiled ("linked") by substituting dynamic blocks into the
 *       template at {@code default-system-prompt.md}. Blocks: {@code {{LAW}}} (VETO.md, resolved
 *       per-root + cross-root), {@code {{IDENTITY}}} (persona name+description plus optional
 *       deployer role guidance), {@code {{ROLE}}} (STANDALONE/LEADER/MATE - drives the tool set),
 *       {@code {{WORKSPACE}}} (session roots + path mode), {@code {{ENVIRONMENT}}} (host OS/arch +
 *       no-shell run_command semantics), {@code {{TOOLS}}} (role-scoped catalog, from the SAME flat
 *       tools that build {@code tools[]}), {@code {{BOUNDARIES}}} (deployer-policy "not-do" fence),
 *       {@code {{SKILLS}}} (name+desc catalog). See {@link PromptTemplate} + {@link PromptBlocks}.
 *   <li><b>messages[]</b> - role-mapped, REWIND-resolved, token-budgeted (pair-safe truncation,
 *       system never trimmed), emitted oldest->newest and passed through {@link #wellFormed} so the
 *       result is the conversation every strict provider accepts (opens on a user message; every
 *       tool_call is answered by a tool_result immediately after it — unanswered calls get a
 *       synthesized "interrupted" result).
 *   <li><b>tools[]</b> + <b>response_schema</b> - via the {@link CapabilityTranslator} (flat tools
 *       + the per-turn {@code veto_pulse} schema variant).
 * </ol>
 */
@Component
public class PromptCompiler {

    /**
     * The tool_result content synthesized for a tool_call the episode never answered (the run was
     * interrupted or the backend stopped between the call and its result). The message is
     * model-facing: it tells the model the call is known-but-unanswered so it can reissue it
     * instead of assuming it ran.
     */
    static final @NonNull String INTERRUPTED_TOOL_RESULT =
            "(tool call interrupted — no result was recorded; reissue the call if its result is"
                    + " still needed)";

    private final @NonNull CapabilityTranslator translator;
    private final @NonNull SystemPromptResolver systemPromptResolver;
    private final com.fasterxml.jackson.databind.@NonNull ObjectMapper objectMapper;
    private final @NonNull ToolResultPresenter toolResultPresenter;

    @Value("${veto.context.max_input_tokens:32000}")
    private int maxInputTokens;

    @Value("${veto.context.context_fill_ratio:0.9}")
    private double contextFillRatio;

    @Value("${veto.security.deployer-policy:FULL_ACCESS}")
    private @NonNull String deployerPolicyRaw = "FULL_ACCESS";

    private @NonNull DeployerPolicy deployerPolicy = DeployerPolicy.FULL_ACCESS;

    public PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull SystemPromptResolver systemPromptResolver,
            com.fasterxml.jackson.databind.@NonNull ObjectMapper objectMapper) {
        this(translator, systemPromptResolver, objectMapper, new ToolResultPresenter(objectMapper));
    }

    @Autowired
    public PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull SystemPromptResolver systemPromptResolver,
            com.fasterxml.jackson.databind.@NonNull ObjectMapper objectMapper,
            @NonNull ToolResultPresenter toolResultPresenter) {
        this.translator = translator;
        this.systemPromptResolver = systemPromptResolver;
        this.objectMapper = objectMapper;
        this.toolResultPresenter = toolResultPresenter;
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
     * @param systemPromptBase optional additional role guidance (e.g. the Mate base from {@code
     *     veto.group.mate.system-prompt-base}); it never replaces persona identity or skillset
     *     context. Role/tools/boundaries are persona-driven.
     * @param history the raw, append-only turn history (oldest->newest)
     * @param guidedSwitch whether this is the guided-switch turn (emits {@code actions})
     */
    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean guidedSwitch,
            double correctionFactor) {
        return compile(
                persona,
                sessionWorkspace,
                systemPromptBase,
                history,
                guidedSwitch,
                correctionFactor,
                ToolResultPresentationMode.BASIC);
    }

    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean guidedSwitch,
            double correctionFactor,
            @NonNull ToolResultPresentationMode toolResultPresentation) {

        List<top.focess.veto.llm.core.ToolDefinition> flatTools =
                translator.translateTools(
                        availableTools(
                                persona.whitelistedTools(), persona.registeredSkills().isEmpty()));
        String linkedSystemMessage =
                buildSystemMessage(
                        persona,
                        sessionWorkspace,
                        systemPromptBase,
                        flatTools,
                        toolResultPresentation);
        ResolvedHistory resolved =
                resolveRewinds(history, linkedSystemMessage, toolResultPresentation);
        String systemMessage = resolved.systemMessage();
        List<ChatMessage> conversation = resolved.messages();
        List<ChatMessage> budgeted = fitBudget(systemMessage, conversation, correctionFactor);
        List<ChatMessage> messages = wellFormed(conversation, budgeted);

        var responseSchema = translator.vetoResponseSchema(guidedSwitch, flatTools);

        int trimmed = conversation.size() - budgeted.size();
        long estimate = Math.round(ceilChars(systemMessage.length()) * correctionFactor);
        for (ChatMessage msg : messages) {
            estimate += Math.round(ceilChars(msg.content().length()) * correctionFactor);
        }
        return new CompiledPrompt(
                systemMessage, messages, flatTools, responseSchema, trimmed, estimate);
    }

    /**
     * Links the current runtime definition for a brand-new AGENT_INIT. Resume compilation must not
     * call this to replace a recorded prompt; {@link #compile} takes the active system prompt from
     * the ordered AGENT_INIT records whenever one exists.
     */
    public @NonNull String linkSystemMessage(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        List<top.focess.veto.llm.core.ToolDefinition> flatTools =
                translator.translateTools(
                        availableTools(
                                persona.whitelistedTools(), persona.registeredSkills().isEmpty()));
        return buildSystemMessage(
                persona, sessionWorkspace, systemPromptBase, flatTools, toolResultPresentation);
    }

    /** Removes conditional capabilities that cannot succeed for this persona. */
    static @NonNull List<top.focess.veto.agent.mcp.@NonNull ToolDefinition> availableTools(
            java.util.@NonNull Collection<top.focess.veto.agent.mcp.@NonNull ToolDefinition> tools,
            boolean skillsEmpty) {
        return tools.stream()
                .filter(tool -> !skillsEmpty || !"load_skill".equals(tool.name()))
                .toList();
    }

    // ── System message (compile/link) ───────────────────────────────────────────

    private @NonNull String buildSystemMessage(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String base,
            @NonNull List<top.focess.veto.llm.core.ToolDefinition> flatTools,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        String law = sessionWorkspace.vetoMdResolver().resolve();
        // Persona identity is always retained. A deployer-supplied role base is additional trusted
        // guidance, not an identity replacement; otherwise Mate id/skillset context disappears.
        String identity = PromptBlocks.identity(persona.name(), persona.description());
        if (base != null && !base.isBlank()) {
            identity += "\n\n## Additional Role Guidance\n" + base.strip();
        }
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("LAW", PromptBlocks.law(law));
        blocks.put("IDENTITY", identity);
        blocks.put("ROLE", PromptBlocks.role(persona.role()));
        blocks.put("WORKSPACE", PromptBlocks.workspace(sessionWorkspace, deployerPolicy));
        boolean commandToolsAvailable =
                flatTools.stream()
                        .anyMatch(
                                tool ->
                                        "run_command".equals(tool.name())
                                                || "run_task".equals(tool.name()));
        blocks.put("ENVIRONMENT", PromptBlocks.environment(commandToolsAvailable));
        blocks.put(
                "RESULT_CONVENTIONS",
                flatTools.isEmpty() ? "" : PromptBlocks.resultConventions(toolResultPresentation));
        blocks.put("TOOLS", PromptBlocks.tools(flatTools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(deployerPolicy));
        blocks.put("SKILLS", PromptBlocks.skills(persona.registeredSkills()));
        return PromptTemplate.render(systemPromptResolver.defaultPrompt(), blocks);
    }

    // ── REWIND resolution ────────────────────────────────────────────

    /**
     * Walks history ascending, applying REWIND suffix-drops; returns the effective compiled list.
     */
    private @NonNull ResolvedHistory resolveRewinds(
            List<TurnRecord> history,
            @NonNull String fallbackSystemMessage,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        List<ChatMessage> compiled = new ArrayList<>();
        String activeSystemMessage = fallbackSystemMessage;
        if (history == null) {
            return new ResolvedHistory(activeSystemMessage, compiled);
        }
        // Pending thought from an ASSISTANT_THOUGHT turn - merged into the next TOOL_CALL's
        // assistant message (as content + reasoningContent) so the model sees its thought and
        // tool call as a single assistant turn, matching the standard tool-calling format.
        String pendingThought = null;
        String pendingReasoning = null;
        for (TurnRecord turn : history) {
            if (turn.type() == TurnType.AGENT_INIT) {
                // AGENT_INIT is an ordered system-prompt insertion event. Do not synthesize,
                // reorder, or replace it with the current runtime template on resume. Provider
                // APIs expose one system/instructions slot, so the last insertion encountered in
                // durable record order is the active system prompt for this request.
                if (pendingThought != null && !pendingThought.isBlank()) {
                    compiled.add(ChatMessage.assistant(pendingThought));
                }
                pendingThought = null;
                pendingReasoning = null;
                activeSystemMessage = str(turn.payload(), "system_prompt");
                continue;
            }
            if (turn.type() == TurnType.REWIND) {
                int fromIndex = number(turn.payload(), "from_index").intValue();
                truncate(compiled, fromIndex);
                String recalledContent = str(turn.payload(), "content");
                if (!recalledContent.isBlank()) {
                    compiled.add(ChatMessage.user(recalledContent));
                }
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
            ChatMessage msg =
                    mapRole(turn, pendingThought, pendingReasoning, toolResultPresentation);
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
        return new ResolvedHistory(activeSystemMessage, compiled);
    }

    private record ResolvedHistory(
            @NonNull String systemMessage, @NonNull List<@NonNull ChatMessage> messages) {}

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
    private ChatMessage mapRole(
            @NonNull TurnRecord turn,
            String pendingThought,
            String pendingReasoning,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        String thoughtContent = pendingThought != null ? pendingThought : "";
        return switch (turn.type()) {
            case USER_PROMPT -> ChatMessage.user(renderUserPrompt(turn.payload()));
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
            case TOOL_RESPONSE -> mapPresentedToolResponse(turn, toolResultPresentation);
            case AGENT_INIT -> null; // handled as an ordered system insertion before role mapping
            case COMPACTION_SUMMARY -> ChatMessage.user(str(turn.payload(), "content"));
            case REWIND -> null;
        };
    }

    static @NonNull ChatMessage mapToolResponse(@NonNull TurnRecord turn) {
        // Raw tool output linked by callId. Synthetic observations have no call id and remain user
        // feedback because provider APIs reject an orphaned tool-result message.
        String callId = str(turn.payload(), "call_id");
        String content = str(turn.payload(), "content");
        Object rawSuccess = turn.payload().get("success");
        boolean success = !(rawSuccess instanceof Boolean value) || value;
        return callId.isBlank()
                ? ChatMessage.user(content)
                : ChatMessage.toolResult(callId, content, success);
    }

    private @NonNull ChatMessage mapPresentedToolResponse(
            @NonNull TurnRecord turn, @NonNull ToolResultPresentationMode toolResultPresentation) {
        String callId = str(turn.payload(), "call_id");
        String content = str(turn.payload(), "content");
        Object rawSuccess = turn.payload().get("success");
        boolean success = !(rawSuccess instanceof Boolean value) || value;
        if (callId.isBlank()) {
            return ChatMessage.user(content);
        }
        ToolResultStatus status = ToolResultStatus.from(turn.payload().get("status"), success);
        ToolResultFormat format = ToolResultFormat.fromId(turn.payload().get("format"));
        String errorCode = str(turn.payload(), "errorCode");
        String presented =
                toolResultPresenter.present(
                        "",
                        callId,
                        status,
                        format,
                        content,
                        errorCode.isBlank() ? null : errorCode,
                        toolResultPresentation);
        return ChatMessage.toolResult(callId, presented, status == ToolResultStatus.SUCCESS);
    }

    /** Serializes the args map to a JSON string for the toolCall arguments field. */
    private @NonNull String serializeArgs(Object args) {
        if (args == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return args.toString();
        }
    }

    private static @NonNull String str(Map<String, Object> payload, @NonNull String key) {
        if (payload == null) {
            return "";
        }
        Object v = payload.get(key);
        return v == null ? "" : v.toString();
    }

    private static @NonNull String renderUserPrompt(@NonNull Map<String, Object> payload) {
        String resumeContext = str(payload, "resume_context");
        if (resumeContext.isBlank()) {
            return str(payload, "content");
        }
        return "Continue the unfinished task from the prior episode. The prior episode stopped "
                + "only because the model-call limit was reached; do not repeat the limit notice. "
                + "Resume from the existing observations and progress.\n\nOriginal user request:\n"
                + resumeContext;
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

    /**
     * Normalizes the budgeted window into the conversation shape <b>every</b> strict provider
     * accepts. This is a provider-agnostic contract, enforced once here for all clients - never a
     * per-provider special case in an adapter. Three invariants:
     *
     * <ol>
     *   <li><b>Every tool_call is answered immediately.</b> An assistant tool_call whose next
     *       message is not the matching tool_result gets a synthesized {@link
     *       #INTERRUPTED_TOOL_RESULT} right after it. This covers dangling calls ANYWHERE in the
     *       window - the production shape is an episode cut off mid-tool (user interrupt, backend
     *       restart) leaving a persisted tool_call with no result, followed by the next user
     *       prompt; strict providers reject that conversation (e.g. MiniMax error 2013). The
     *       synthesis is compile-time only - the persisted turn log is never rewritten.
     *   <li><b>No orphaned tool_result.</b> A {@code tool} message not paired with the assistant
     *       tool_call emitted just before it is demoted to a user text message - its content is
     *       context, preserved rather than dropped.
     *   <li><b>First message is {@code user}.</b> Strict providers reject a conversation that opens
     *       on an assistant/tool turn, which the budget can produce once the opening user turn is
     *       trimmed. Re-anchor on the episode's opening user prompt (the last user message of the
     *       pre-budget list) so the window stays truthful; a minimal marker covers the rare case
     *       where no user message exists.
     * </ol>
     *
     * <p>Package-private and static so the contract is unit-testable without a full compile.
     *
     * @param full the pre-budget compiled conversation (oldest→newest), used to recover the anchor
     * @param window the budgeted conversation (oldest→newest)
     */
    static @NonNull List<ChatMessage> wellFormed(
            @NonNull List<ChatMessage> full, @NonNull List<ChatMessage> window) {
        List<ChatMessage> out = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            ChatMessage m = window.get(i);
            String callId = m.callId();
            if ("assistant".equals(m.role()) && callId != null && !callId.isBlank()) {
                out.add(m);
                if (!isAnsweredImmediately(window, i, m)) {
                    out.add(ChatMessage.toolResult(callId, INTERRUPTED_TOOL_RESULT, false));
                }
                continue;
            }
            if ("tool".equals(m.role())) {
                ChatMessage prev = out.isEmpty() ? null : out.get(out.size() - 1);
                boolean paired =
                        prev != null
                                && "assistant".equals(prev.role())
                                && callId != null
                                && callId.equals(prev.callId());
                if (!paired) {
                    out.add(ChatMessage.user(m.content()));
                    continue;
                }
            }
            out.add(m);
        }
        // Invariant 3: the conversation must open on a user message; re-anchor if the budget
        // trimmed the opening user turn (or the window collapsed entirely).
        if (out.isEmpty() || !"user".equals(out.get(0).role())) {
            String anchor = lastUserContent(full);
            out.add(0, ChatMessage.user(anchor != null ? anchor : "(continued)"));
        }
        return out;
    }

    /** True when the tool_result for the call at {@code index} is the very next message. */
    private static boolean isAnsweredImmediately(
            @NonNull List<ChatMessage> window, int index, @NonNull ChatMessage call) {
        ChatMessage next = index + 1 < window.size() ? window.get(index + 1) : null;
        return next != null
                && "tool".equals(next.role())
                && java.util.Objects.equals(call.callId(), next.callId());
    }

    private static @NonNull Number number(
            @NonNull Map<String, Object> payload, @NonNull String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("Turn payload '" + key + "' must be numeric");
    }

    /** The content of the last user-role message (the episode's opening prompt), or null. */
    private static String lastUserContent(@NonNull List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role()) && !m.content().isBlank()) {
                return m.content();
            }
        }
        return null;
    }

    private static int contentLen(@NonNull ChatMessage msg) {
        return msg.content().length();
    }

    private static long ceilChars(int chars) {
        return (long) Math.ceil(chars / 3.0);
    }
}
