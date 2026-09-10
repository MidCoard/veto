package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.HistoryProjection;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolResultStatus;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.VetoRequest;

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
 *       {@code {{WORKSPACE}}} (session roots + usable path syntax), {@code {{ENVIRONMENT}}} (host
 *       OS/arch + no-shell run_command semantics), {@code {{TOOLS}}} (role-scoped catalog, from the
 *       SAME flat tools that build {@code tools[]}), {@code {{BOUNDARIES}}} (deployer-policy
 *       "not-do" fence), {@code {{SKILLS}}} (name+desc catalog). See {@link PromptTemplate} +
 *       {@link PromptBlocks}.
 *   <li><b>messages[]</b> - role-mapped, REWIND-resolved, and checked against the input budget
 *       without silently removing conversation history, emitted oldest->newest and passed through
 *       {@link #wellFormed} so the result is the conversation every strict provider accepts (opens
 *       on a user message; every tool_call is answered by a tool_result immediately after it —
 *       unanswered calls get a synthesized "interrupted" result).
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
    private final SystemPromptResolver systemPromptResolver;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull ToolResultPresenter toolResultPresenter;

    @Value("${veto.context.max_input_tokens}")
    private int maxInputTokens;

    @Value("${veto.context.context_fill_ratio}")
    private double contextFillRatio;

    private final @NonNull DeployerPolicy deployerPolicy;
    private final String isolatedInstructions;

    private @NonNull Map<String, Integer> modelInputTokens = Map.of();

    @Autowired
    void configureContextBudgets(@NonNull ContextBudgetConfiguration configuration) {
        modelInputTokens = configuration.getModelInputTokens();
    }

    public PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull SystemPromptResolver systemPromptResolver,
            @NonNull ObjectMapper objectMapper,
            @NonNull String deployerPolicyRaw) {
        this(
                translator,
                systemPromptResolver,
                objectMapper,
                new ToolResultPresenter(objectMapper),
                deployerPolicyRaw);
    }

    @Autowired
    public PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull SystemPromptResolver systemPromptResolver,
            @NonNull ObjectMapper objectMapper,
            @NonNull ToolResultPresenter toolResultPresenter,
            @Value("${veto.security.deployer-policy}") @NonNull String deployerPolicyRaw) {
        this.translator = translator;
        this.systemPromptResolver = systemPromptResolver;
        this.objectMapper = objectMapper;
        this.toolResultPresenter = toolResultPresenter;
        this.deployerPolicy = DeployerPolicy.parse(deployerPolicyRaw);
        this.isolatedInstructions = null;
    }

    private PromptCompiler(
            @NonNull CapabilityTranslator translator,
            @NonNull ObjectMapper mapper,
            @NonNull String instructions,
            int maxInputTokens) {
        if (instructions.isBlank() || maxInputTokens <= 0)
            throw new IllegalArgumentException(
                    "Isolated prompt needs instructions and a positive input budget");
        this.translator = translator;
        this.systemPromptResolver = null;
        this.objectMapper = mapper;
        this.toolResultPresenter = new ToolResultPresenter(mapper);
        this.deployerPolicy = DeployerPolicy.PROTECTED;
        this.isolatedInstructions =
                PromptTemplate.render(
                        SystemPromptResolver.loadRules("veto/default-tool-agent-system-prompt.md"),
                        Map.of(
                                "OPERATING_CONTRACT",
                                        SystemPromptResolver.loadRules(
                                                "veto/tool-agent-operating-contract.md"),
                                "TASK_INSTRUCTIONS", "## Task Instructions\n\n" + instructions,
                                "TOOL_CALLS",
                                        SystemPromptResolver.loadRules(
                                                "veto/tool-agent-tool-calls.md"),
                                "RESPONSE_PROTOCOL",
                                        SystemPromptResolver.loadRules(
                                                "veto/tool-agent-response-protocol.md")));
        this.maxInputTokens = maxInputTokens;
        this.contextFillRatio = 1;
    }

    /**
     * Links the tool-agent template without resolving workspace, group role, skills, or
     * environment.
     */
    public static @NonNull PromptCompiler isolated(
            @NonNull CapabilityTranslator translator,
            @NonNull ObjectMapper mapper,
            @NonNull String instructions,
            int maxInputTokens) {
        return new PromptCompiler(translator, mapper, instructions, maxInputTokens);
    }

    /** Reapplies isolated budgeting after transient schema-repair observations are appended. */
    public @NonNull VetoRequest fitRequest(@NonNull VetoRequest request) {
        return fitRequest(request, 1.0);
    }

    public @NonNull VetoRequest fitRequest(@NonNull VetoRequest request, double correctionFactor) {
        if (isolatedInstructions == null) {
            requireBudget(
                    request.systemPrompt(),
                    request.messages(),
                    request.tools(),
                    request.responseSchema(),
                    correctionFactor,
                    request.options().contextWindowTokens() != null
                            ? request.options().inputBudget()
                            : inputBudget(request.providerType().name(), request.modelName()));
            return request;
        }
        List<ChatMessage> conversation = request.messages();
        List<ChatMessage> messages =
                new ArrayList<>(
                        fitIsolatedBudget(conversation, request.tools(), request.responseSchema()));
        return new VetoRequest(
                request.systemPrompt(),
                request.userPrompt(),
                request.tools(),
                request.providerType(),
                request.modelName(),
                request.credentialKey(),
                request.options(),
                messages,
                request.responseSchema(),
                request.baseUrl());
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
     * @param guidedEnabled whether the session permits guided programs
     */
    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean guidedEnabled,
            double correctionFactor) {
        return compile(
                persona,
                sessionWorkspace,
                systemPromptBase,
                history,
                guidedEnabled,
                correctionFactor,
                ToolResultPresentationMode.BASIC);
    }

    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean guidedEnabled,
            double correctionFactor,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        return compile(
                persona,
                sessionWorkspace,
                systemPromptBase,
                history,
                guidedEnabled,
                correctionFactor,
                toolResultPresentation,
                null);
    }

    public @NonNull CompiledPrompt compile(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean guidedEnabled,
            double correctionFactor,
            @NonNull ToolResultPresentationMode toolResultPresentation,
            Long inputBudgetOverride) {

        List<ToolDefinition> flatTools =
                translator.translateTools(
                        availableTools(
                                persona.whitelistedTools(), persona.registeredSkills().isEmpty()));
        List<ChatMessage> conversation = resolveRewinds(history, toolResultPresentation);
        String systemMessage =
                conversation.stream()
                        .filter(message -> "system".equals(message.role()))
                        .map(ChatMessage::content)
                        .collect(Collectors.joining("\n\n"));
        if (isolatedInstructions != null) {
            var schema = translator.vetoResponseSchema(false, flatTools);
            List<ChatMessage> messages = fitIsolatedBudget(conversation, flatTools, schema);
            return new CompiledPrompt(
                    systemMessage,
                    messages,
                    flatTools,
                    schema,
                    Math.max(0, conversation.size() - messages.size()),
                    isolatedSize(messages, flatTools, schema));
        }
        List<ChatMessage> messages = wellFormed(conversation, conversation);

        var responseSchema = translator.vetoResponseSchema(guidedEnabled, flatTools);

        String provider = "";
        String model = "";
        for (TurnRecord turn : HistoryProjection.effective(history != null ? history : List.of())) {
            if (turn.type() == TurnType.AGENT_INIT) {
                provider = str(turn.payload(), "provider");
                model = str(turn.payload(), "model");
            }
        }
        long estimate =
                requireBudget(
                        systemMessage,
                        messages,
                        flatTools,
                        responseSchema,
                        correctionFactor,
                        inputBudgetOverride != null
                                ? inputBudgetOverride
                                : inputBudget(provider, model));
        return new CompiledPrompt(systemMessage, messages, flatTools, responseSchema, 0, estimate);
    }

    /** Builds the system prompt stored in a newly created AGENT_INIT record. */
    public @NonNull String linkSystemMessage(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        return linkSystemMessage(
                persona, sessionWorkspace, systemPromptBase, toolResultPresentation, false);
    }

    public @NonNull String linkSystemMessage(
            @NonNull AgentPersona persona,
            @NonNull Workspace sessionWorkspace,
            String systemPromptBase,
            @NonNull ToolResultPresentationMode toolResultPresentation,
            boolean guidedEnabled) {
        List<ToolDefinition> flatTools =
                translator.translateTools(
                        availableTools(
                                persona.whitelistedTools(), persona.registeredSkills().isEmpty()));
        return buildSystemMessage(
                persona,
                sessionWorkspace,
                systemPromptBase,
                flatTools,
                toolResultPresentation,
                guidedEnabled);
    }

    /** Removes conditional capabilities that cannot succeed for this persona. */
    static @NonNull List<top.focess.veto.agent.tool.@NonNull ToolDefinition> availableTools(
            @NonNull Collection<top.focess.veto.agent.tool.@NonNull ToolDefinition> tools,
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
            @NonNull List<ToolDefinition> flatTools,
            @NonNull ToolResultPresentationMode toolResultPresentation,
            boolean guidedEnabled) {
        String fixed = isolatedInstructions;
        if (fixed != null) {
            return PromptTemplate.render(
                    fixed,
                    Map.of(
                            "IDENTITY",
                                    PromptBlocks.identity(persona.name(), persona.description()),
                            "TOOLS", PromptBlocks.tools(flatTools),
                            "RESULT_CONVENTIONS",
                                    PromptBlocks.resultConventions(toolResultPresentation)));
        }
        SystemPromptResolver resolver = systemPromptResolver;
        if (resolver == null) throw new IllegalStateException("Missing standard prompt resolver");
        String law = sessionWorkspace.vetoMdResolver().resolve();
        // Persona identity is always retained. A deployer-supplied role base is additional trusted
        // guidance, not an identity replacement; otherwise Mate id/skillset context disappears.
        String identity = PromptBlocks.identity(persona.name(), persona.description());
        if (base != null && !base.isBlank()) {
            identity += "\n\n## Additional Role Guidance\n" + base.strip();
        }
        Map<String, String> blocks = new LinkedHashMap<>(resolver.commonBlocks());
        blocks.put("LAW", PromptBlocks.law(law));
        blocks.put("IDENTITY", identity);
        blocks.put("ROLE", PromptBlocks.role(persona.role()));
        blocks.put(
                "DELEGATION_RULES",
                flatTools.stream().anyMatch(tool -> "create_group".equals(tool.name()))
                        ? resolver.delegationPrompt()
                        : "");
        blocks.put("WORKSPACE", PromptBlocks.workspace(sessionWorkspace));
        blocks.put("ENVIRONMENT", PromptBlocks.environment());
        blocks.put(
                "RESULT_CONVENTIONS",
                flatTools.isEmpty() ? "" : PromptBlocks.resultConventions(toolResultPresentation));
        blocks.put("TOOLS", PromptBlocks.tools(flatTools));
        blocks.put("GUIDED_PROTOCOL", guidedEnabled ? resolver.guidedPrompt() : "");
        blocks.put(
                "BOUNDARIES", PromptBlocks.boundaries(deployerPolicy, sessionWorkspace.pathMode()));
        blocks.put("SKILLS", PromptBlocks.skills(persona.registeredSkills()));
        return PromptTemplate.render(resolver.defaultPrompt(), blocks);
    }

    private @NonNull List<ChatMessage> fitIsolatedBudget(
            @NonNull List<ChatMessage> conversation,
            @NonNull List<ToolDefinition> tools,
            JsonNode schema) {
        List<ChatMessage> messages = new ArrayList<>(wellFormed(conversation, conversation));
        // The opening user message is the invocation's objective. Always retain it while removing
        // old call/result pairs; never substitute a later tool error as the task anchor.
        while (isolatedSize(messages, tools, schema) > maxInputTokens) {
            if (messages.size() <= 3)
                throw new IllegalStateException(
                        "Isolated agent's latest observation exceeds its input budget");
            int removeIndex = 0;
            while (removeIndex < messages.size()
                    && "system".equals(messages.get(removeIndex).role())) removeIndex++;
            removeIndex++; // Preserve the invocation objective.
            if (messages.size() - removeIndex <= 2)
                throw new IllegalStateException("Isolated agent input exceeds budget");
            ChatMessage removed = messages.remove(removeIndex);
            if (removed.callId() != null
                    && "tool".equals(messages.get(removeIndex).role())
                    && Objects.equals(removed.callId(), messages.get(removeIndex).callId()))
                messages.remove(removeIndex);
        }
        return messages;
    }

    private long isolatedSize(
            @NonNull List<ChatMessage> messages,
            @NonNull List<ToolDefinition> tools,
            JsonNode schema) {
        // Serialized UTF-8 bytes conservatively account for content, reasoning, tool arguments,
        // tool definitions, schema, and message framing without assuming English token density.
        try {
            return objectMapper.writeValueAsBytes(messages).length
                    + objectMapper.writeValueAsBytes(tools).length
                    + (schema == null ? 4 : objectMapper.writeValueAsBytes(schema).length)
                    + 256L;
        } catch (Exception error) {
            throw new IllegalStateException("Could not measure isolated model input", error);
        }
    }

    // ── REWIND resolution ────────────────────────────────────────────

    /**
     * Walks history ascending, applying REWIND suffix-drops; returns the effective compiled list.
     */
    @NonNull List<ChatMessage> resolveRewinds(
            List<TurnRecord> history, @NonNull ToolResultPresentationMode toolResultPresentation) {
        List<ChatMessage> compiled = new ArrayList<>();
        if (history == null) {
            return compiled;
        }
        // Pending thought from an ASSISTANT_THOUGHT turn - merged into the next TOOL_CALL's
        // assistant message (as content + reasoningContent) so the model sees its thought and
        // tool call as a single assistant turn, matching the standard tool-calling format.
        String pendingThought = null;
        String pendingReasoning = null;
        Integer pendingTurn = null;
        for (TurnRecord turn : HistoryProjection.effective(history)) {
            if (turn.type() == TurnType.AGENT_INIT) {
                if (pendingThought != null && !pendingThought.isBlank()) {
                    compiled.add(
                            ChatMessage.assistant(pendingThought)
                                    .withSourceTurns(
                                            pendingTurn == null
                                                    ? List.of()
                                                    : List.of(pendingTurn)));
                }
                pendingThought = null;
                pendingReasoning = null;
                pendingTurn = null;
                compiled.add(ChatMessage.system(str(turn.payload(), "system_prompt")));
                continue;
            }
            if (turn.type() == TurnType.REWIND) {
                String recalledContent = str(turn.payload(), "content");
                if (!recalledContent.isBlank()) {
                    compiled.add(
                            ChatMessage.user(recalledContent)
                                    .withSourceTurns(List.of(turn.turnNumber())));
                }
                pendingThought = null;
                pendingReasoning = null;
                pendingTurn = null;
                continue;
            }
            if (turn.type() == TurnType.ASSISTANT_THOUGHT) {
                // Buffer the thought + reasoning_content; merge into the next TOOL_CALL or
                // ASSISTANT_RESPONSE. Not emitted as a standalone message (saves tokens and
                // avoids DeepSeek's reasoning_content echo requirement on thought-only messages).
                pendingThought = str(turn.payload(), "response");
                pendingTurn = turn.turnNumber();
                pendingReasoning = str(turn.payload(), "reasoning_content");
                if (pendingReasoning.isBlank()) {
                    pendingReasoning = null;
                }
                continue;
            }
            ChatMessage msg =
                    mapRole(turn, pendingThought, pendingReasoning, toolResultPresentation);
            if (msg != null) {
                compiled.add(
                        msg.withSourceTurns(
                                pendingTurn != null && turn.type() == TurnType.TOOL_CALL
                                        ? List.of(pendingTurn, turn.turnNumber())
                                        : List.of(turn.turnNumber())));
            }
            pendingThought = null;
            pendingReasoning = null;
            pendingTurn = null;
        }
        // A trailing thought with no following turn (e.g. thought + STOP) - emit as assistant.
        if (pendingThought != null && !pendingThought.isBlank()) {
            compiled.add(
                    ChatMessage.assistant(pendingThought)
                            .withSourceTurns(
                                    pendingTurn == null ? List.of() : List.of(pendingTurn)));
        }
        return compiled;
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
            case MONITOR_EVENT ->
                    ChatMessage.user("[Monitor observation] " + str(turn.payload(), "content"));
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
            case AGENT_INIT -> null; // handled before role mapping
            case COMPACTION_SUMMARY -> ChatMessage.user(str(turn.payload(), "content"));
            case REWIND, TOKEN_USAGE -> null;
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
        // New records persist the exact model-visible representation. Older records predate that
        // invariant and contain canonical tool content, so only those rows need presentation at
        // replay time.
        if (turn.payload().containsKey("presentation")) {
            return ChatMessage.toolResult(callId, content, status == ToolResultStatus.SUCCESS);
        }
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

    private long inputBudget(@NonNull String provider, @NonNull String model) {
        Integer configured = modelInputTokens.get(provider + "/" + model);
        int limit = configured != null ? configured : maxInputTokens;
        if (limit <= 0 || contextFillRatio <= 0 || contextFillRatio > 1) {
            throw new IllegalStateException("Invalid context input budget configuration");
        }
        return (long) (limit * contextFillRatio);
    }

    /** Preserve complete context or stop explicitly; never silently remove earlier conversation. */
    private long requireBudget(
            @NonNull String system,
            @NonNull List<ChatMessage> messages,
            @NonNull List<ToolDefinition> tools,
            JsonNode schema,
            double factor,
            long budget) {
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException("Invalid context token correction factor");
        }
        long bytes;
        try {
            // Count the system once; include tool arguments, reasoning, catalog, schema and
            // framing.
            bytes =
                    objectMapper.writeValueAsBytes(
                                    Map.of(
                                            "system",
                                            system,
                                            "messages",
                                            messages.stream()
                                                    .filter(m -> !"system".equals(m.role()))
                                                    .toList(),
                                            "tools",
                                            tools,
                                            "schema",
                                            schema != null ? schema : objectMapper.nullNode()))
                            .length;
        } catch (Exception error) {
            throw new IllegalStateException("Could not measure model input", error);
        }
        long estimate = (long) Math.ceil((bytes / 3.0 + 256) * factor);
        if (estimate > budget) {
            throw new IllegalStateException(
                    "Context input budget exceeded: estimated "
                            + estimate
                            + " tokens, budget "
                            + budget
                            + ". No conversation history was removed. Compact the session or "
                            + "configure a larger input budget supported by this model.");
        }
        return estimate;
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
                    out.add(ChatMessage.user(m.content()).withSourceTurns(m.sourceTurns()));
                    continue;
                }
            }
            out.add(m);
        }
        // Invariant 3: the conversation must open on a user message; re-anchor if the budget
        // trimmed the opening user turn (or the window collapsed entirely).
        int firstConversation = 0;
        while (firstConversation < out.size() && "system".equals(out.get(firstConversation).role()))
            firstConversation++;
        if (firstConversation == out.size() || !"user".equals(out.get(firstConversation).role())) {
            ChatMessage anchor = lastUserMessage(full);
            out.add(firstConversation, anchor != null ? anchor : ChatMessage.user("(continued)"));
        }
        return out;
    }

    /** True when the tool_result for the call at {@code index} is the very next message. */
    private static boolean isAnsweredImmediately(
            @NonNull List<ChatMessage> window, int index, @NonNull ChatMessage call) {
        ChatMessage next = index + 1 < window.size() ? window.get(index + 1) : null;
        return next != null
                && "tool".equals(next.role())
                && Objects.equals(call.callId(), next.callId());
    }

    /** The last user-role message, including provenance when re-anchoring a trimmed window. */
    private static ChatMessage lastUserMessage(@NonNull List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role()) && !m.content().isBlank()) {
                return m;
            }
        }
        return null;
    }
}
