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
 *       {@code {{ROLE}}} (STANDALONE/LEADER/MATE - drives the tool set), {@code {{TOOLS}}}
 *       (role-scoped catalog, from the SAME flat tools that build {@code tools[]}), {@code
 *       {{BOUNDARIES}}} (deployer-policy "not-do" fence), {@code {{SKILLS}}} (name+desc catalog).
 *       See {@link PromptTemplate} + {@link PromptBlocks}.
 *   <li><b>messages[]</b> - raw history newest->oldest, role-mapped per, REWIND-resolved per ,
 *       token-budgeted with pair-safe truncation (system never trimmed).
 *   <li><b>tools[]</b> + <b>response_schema</b> - via the {@link CapabilityTranslator} (flat tools
 *       + the per-turn {@code veto_pulse} schema variant).
 * </ol>
 */
@Component
public class PromptCompiler {

    private final @NonNull CapabilityTranslator translator;
    private final @NonNull SystemPromptResolver systemPromptResolver;

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
            @NonNull SystemPromptResolver systemPromptResolver) {
        this.translator = translator;
        this.systemPromptResolver = systemPromptResolver;
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
                buildSystemMessage(
                        persona, sessionWorkspace, systemPromptBase, flatTools, guidedSwitch);
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
            @NonNull List<top.focess.veto.llm.core.ToolDefinition> flatTools,
            boolean guidedSwitch) {
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
        for (TurnRecord turn : history) {
            if (turn.type() == TurnType.REWIND) {
                int fromIndex = (Integer) turn.payload().get("from_index");
                truncate(compiled, fromIndex);
                continue;
            }
            if (turn.type() == TurnType.RECALL) {
                // Composite directive: suffix-drop to from_index (keep the seed turns, e.g.
                // AGENT_INIT), then re-inject the recalled brief as a user message.
                int fromIndex = (Integer) turn.payload().get("from_index");
                truncate(compiled, fromIndex);
                compiled.add(ChatMessage.user(str(turn.payload(), "content")));
                continue;
            }
            ChatMessage msg = mapRole(turn);
            if (msg != null) {
                compiled.add(msg);
            }
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

    /** Role mapping per. */
    private @Nullable ChatMessage mapRole(@NonNull TurnRecord turn) {
        return switch (turn.type()) {
            case USER_PROMPT -> ChatMessage.user(str(turn.payload(), "content"));
            case USER_INTERRUPT ->
                    ChatMessage.user("[User feedback]: " + str(turn.payload(), "feedback"));
            case ASSISTANT_THOUGHT -> ChatMessage.assistant(str(turn.payload(), "response"));
            case ASSISTANT_RESPONSE -> ChatMessage.assistant(str(turn.payload(), "content"));
            case TOOL_CALL ->
                    ChatMessage.assistant(
                            str(turn.payload(), "response") != null
                                    ? str(turn.payload(), "response")
                                    : toolCallRepr(turn));
            case TOOL_RESPONSE -> ChatMessage.tool(str(turn.payload(), "content"));
            // AGENT_INIT is a role-segment marker only - the front system message (rebuilt each
            // compile from the persona's role) already carries the role, so emitting one here would
            // duplicate it. Compaction still uses the raw AGENT_INIT turn as its segment anchor.
            case AGENT_INIT -> null;
            case COMPACTION_SUMMARY -> ChatMessage.user(str(turn.payload(), "content"));
            case REWIND -> null; // directive, not a message
            case RECALL -> null; // directive, handled in resolveRewinds
        };
    }

    private @NonNull String toolCallRepr(@NonNull TurnRecord turn) {
        return "Tool call: "
                + str(turn.payload(), "tool_name")
                + " args="
                + turn.payload().get("args");
    }

    private static @NonNull String str(@Nullable Map<String, Object> payload, @NonNull String key) {
        if (payload == null) {
            return "";
        }
        Object v = payload.get(key);
        return v == null ? "" : v.toString();
    }

    // ── Token budget ──────────────────────────────────────────────────

    /** Walks newest->oldest, keeping turns until the budget is exceeded (system never trimmed). */
    private @NonNull List<ChatMessage> fitBudget(
            @NonNull String systemMessage,
            @NonNull List<ChatMessage> conversation,
            double correctionFactor) {
        long budget = (long) (maxInputTokens * contextFillRatio);
        long estimate = Math.round(ceilChars(systemMessage.length()) * correctionFactor);
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ChatMessage msg = conversation.get(i);
            long turnEstimate =
                    Math.round(
                            ceilChars(msg.content() == null ? 0 : msg.content().length())
                                    * correctionFactor);
            if (estimate + turnEstimate > budget && !kept.isEmpty()) {
                break; // stop adding - these old turns won't fit
            }
            kept.add(0, msg);
            estimate += turnEstimate;
        }
        return kept;
    }

    private static long ceilChars(int chars) {
        return (long) Math.ceil(chars / 3.0);
    }
}
