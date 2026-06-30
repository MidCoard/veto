package top.focess.veto.agent.loop;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.skills.Skill;
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
 *   <li><b>System message</b> = Layer 1 (The Law / VETO.md resolved per-root + cross-root, then
 *       persona identity + role + response-format rules + native tool usage) + Layer 2 (skill
 *       name+description catalog) + Layer 3 (per-turn JSON-constraint reminder, picking the
 *       variant-ON/OFF schema by the effective thought flag).
 *   <li><b>messages[]</b> — raw history newest→oldest, role-mapped per, REWIND-resolved per ,
 *       token-budgeted with pair-safe truncation (system never trimmed).
 *   <li><b>tools[]</b> + <b>response_schema</b> — via the {@link CapabilityTranslator} (flat tools
 *       + the per-turn {@code veto_pulse} schema variant).
 * </ol>
 */
@Component
public class PromptCompiler {

    private final @NonNull CapabilityTranslator translator;
    private final @NonNull Workspace workspace;

    @Value("${veto.context.max_input_tokens:32000}")
    private int maxInputTokens;

    @Value("${veto.context.context_fill_ratio:0.9}")
    private double contextFillRatio;

    public
    @NonNull
    PromptCompiler(@NonNull CapabilityTranslator translator, @NonNull Workspace workspace) {
        this.translator = translator;
        this.workspace = workspace;
    }

    /**
     * Compiles the payload for one loop cycle.
     *
     * @param persona the agent's identity + resolved manifest + registered skills
     * @param systemPromptBase the Layer-1 base (persona identity + role + response-format rules) —
     *     resolved from the LLM config or {@code ~/.veto/}
     * @param history the raw, append-only turn history (oldest→newest)
     * @param effectiveThoughtFlag the effective thought flag for THIS turn (ON at a user-prompt
     *     turn)
     * @param guidedSwitch whether this is the guided-switch turn (emits {@code actionsProgram})
     */
    public CompiledPrompt compile(
            AgentPersona persona,
            String systemPromptBase,
            List<TurnRecord> history,
            boolean effectiveThoughtFlag,
            boolean guidedSwitch,
            double correctionFactor) {

        String systemMessage =
                buildSystemMessage(persona, systemPromptBase, effectiveThoughtFlag, guidedSwitch);
        List<ChatMessage> conversation = resolveRewinds(history);
        List<ChatMessage> budgeted = fitBudget(systemMessage, conversation, correctionFactor);

        var tools = translator.translateTools(List.copyOf(persona.whitelistedTools()));
        var responseSchema = translator.vetoResponseSchema(effectiveThoughtFlag, guidedSwitch);

        int trimmed = conversation.size() - budgeted.size();
        long estimate = Math.round(ceilChars(systemMessage.length()) * correctionFactor);
        for (ChatMessage msg : budgeted) {
            estimate +=
                    Math.round(
                            ceilChars(msg.content() == null ? 0 : msg.content().length())
                                    * correctionFactor);
        }
        return new CompiledPrompt(
                systemMessage, budgeted, tools, responseSchema, trimmed, estimate);
    }

    // ── System message (3 layers) ───────────────────────────────────────────

    private String buildSystemMessage(
            AgentPersona persona, String base, boolean thoughtFlag, boolean guidedSwitch) {
        StringBuilder sb = new StringBuilder();
        // Layer 1 — The Law (VETO.md, resolved per-root + cross-root) + persona identity + role +
        // response-format rules + native tool usage. The Law is reserved (never truncated).
        String law = workspace.vetoMdResolver().resolve();
        if (law != null && !law.isBlank()) {
            sb.append(law).append("\n\n");
        }
        sb.append(base == null || base.isBlank() ? defaultPersonaBase(persona) : base);
        // Layer 2 — skill catalog (name + description only; bodies via load_skill).
        List<Skill> skills = persona.registeredSkills();
        if (skills != null && !skills.isEmpty()) {
            sb.append(
                    "\n\n=== Available Skills (call load_skill(skillName) to load full instructions) ===\n");
            for (Skill s : skills) {
                sb.append("- ").append(s.name()).append(": ").append(s.description()).append('\n');
            }
        }
        // Layer 3 — per-turn JSON-constraint reminder.
        sb.append("\n\n=== Response Format (veto_pulse) ===\n");
        sb.append("You must always respond with valid JSON matching the veto_pulse schema.\n");
        sb.append("Required fields: is_finished, features.\n");
        sb.append("features = {guided (bool), thought (bool)} — describes the NEXT iteration.\n");
        sb.append("thought field: STRICTLY controlled by this turn's effective thought flag — ");
        sb.append(
                thoughtFlag
                        ? "ON → thought is REQUIRED (present, non-empty).\n"
                        : "OFF → thought is FORBIDDEN (the field is removed from the schema).\n");
        sb.append("Effective thought flag for THIS turn: ")
                .append(thoughtFlag ? "ON" : "OFF")
                .append(".\n");
        sb.append("Optional fields: calls (array), message (string), ");
        sb.append("actionsProgram (object, only when features.guided is true).\n");
        sb.append("(No delegationSpawn field — delegations are create_group tool calls.)\n");
        return sb.toString();
    }

    private String defaultPersonaBase(AgentPersona persona) {
        return "You are "
                + (persona == null ? "a Veto agent" : persona.name())
                + ". "
                + (persona != null && persona.description() != null ? persona.description() : "")
                + "\nRespond in the veto_pulse JSON schema.";
    }

    // ── REWIND resolution ────────────────────────────────────────────

    /**
     * Walks history ascending, applying REWIND suffix-drops; returns the effective compiled list.
     */
    private List<ChatMessage> resolveRewinds(List<TurnRecord> history) {
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
            ChatMessage msg = mapRole(turn);
            if (msg != null) {
                compiled.add(msg);
            }
        }
        return compiled;
    }

    private static void truncate(List<ChatMessage> compiled, int fromIndex) {
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        while (compiled.size() > fromIndex) {
            compiled.remove(compiled.size() - 1);
        }
    }

    /** Role mapping per. */
    private ChatMessage mapRole(TurnRecord turn) {
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
            case AGENT_INIT -> ChatMessage.system(str(turn.payload(), "content"));
            case COMPACTION_SUMMARY -> ChatMessage.user(str(turn.payload(), "content"));
            case REWIND -> null; // directive, not a message
        };
    }

    private String toolCallRepr(TurnRecord turn) {
        return "Tool call: "
                + str(turn.payload(), "tool_name")
                + " args="
                + turn.payload().get("args");
    }

    private static String str(java.util.Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object v = payload.get(key);
        return v == null ? "" : v.toString();
    }

    // ── Token budget ──────────────────────────────────────────────────

    /** Walks newest→oldest, keeping turns until the budget is exceeded (system never trimmed). */
    private List<ChatMessage> fitBudget(
            String systemMessage, List<ChatMessage> conversation, double correctionFactor) {
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
                break; // stop adding — these old turns won't fit
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
