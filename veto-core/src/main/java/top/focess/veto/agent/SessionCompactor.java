package top.focess.veto.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.llm.core.*;

/**
 * Compresses long-running agent sessions by summarizing old turns. When the accumulated context
 * exceeds a token threshold, the oldest turns are replaced with a single compacted summary turn —
 * preserving the important information while freeing context window space.
 *
 * <p>The compactor uses the LLM itself to produce the summary. Important extracted facts are kept
 * in a memory buffer that persists across compactions, forming the basis of Session LTM.
 */
public class SessionCompactor {

    private static final Logger log = LoggerFactory.getLogger(SessionCompactor.class);

    /**
     * Trigger compaction when estimated tokens exceed this.
     */
    private static final int TOKEN_THRESHOLD = 8000;

    /** Keep at least this many recent turns uncompacted. */
    private static final int KEEP_RECENT = 5;

    /** Approximate tokens per character (conservative estimate). */
    private static final double CHARS_PER_TOKEN = 3.5;

    private final UniformLLMCaller caller;
    private String memory; // accumulated facts across compactions

    public SessionCompactor(UniformLLMCaller caller) {
        this.caller = caller;
        this.memory = "";
    }

    /** Estimates the token count from the agent's turns. */
    public int estimateTokens(Agent agent) {
        int chars = agent.systemPrompt().length();
        for (var t : agent.turns()) {
            chars += t.thought() != null ? t.thought().length() : 0;
            chars += t.observation() != null ? t.observation().length() : 0;
        }
        return (int) (chars / CHARS_PER_TOKEN);
    }

    /** Returns true if compaction is needed. */
    public boolean shouldCompact(Agent agent) {
        return agent.turns().size() > KEEP_RECENT && estimateTokens(agent) > TOKEN_THRESHOLD;
    }

    /**
     * Compacts the agent's turn history. Old turns are summarized into one turn. Recent turns are
     * kept intact. Accumulated memory from previous compactions is included.
     */
    public Agent compact(Agent agent) {
        List<TurnRecord> turns = agent.turns();
        int splitAt = Math.max(0, turns.size() - KEEP_RECENT);
        if (splitAt == 0) return agent;

        List<TurnRecord> oldTurns = turns.subList(0, splitAt);
        List<TurnRecord> recentTurns = turns.subList(splitAt, turns.size());

        String summary = buildSummary(oldTurns);
        memory = extractMemory(summary);

        List<TurnRecord> compacted = new ArrayList<>();
        compacted.add(
                new TurnRecord(
                        0,
                        "[Compacted "
                                + oldTurns.size()
                                + " turns]\n"
                                + summary
                                + (memory.isEmpty() ? "" : "\n\nKey facts:\n" + memory),
                        null,
                        null,
                        null,
                        null));
        compacted.addAll(recentTurns);

        // Renumber turns
        List<TurnRecord> renumbered = new ArrayList<>();
        for (int i = 0; i < compacted.size(); i++) {
            var t = compacted.get(i);
            renumbered.add(
                    new TurnRecord(
                            i + 1,
                            t.thought(),
                            t.callToolName(),
                            t.callArgs(),
                            t.observation(),
                            t.timestamp()));
        }

        log.info(
                "Compacted {} turns → 1 summary. Memory: {} chars",
                oldTurns.size(),
                memory.length());
        return Agent.builder()
                .id(agent.id())
                .name(agent.name())
                .systemPrompt(agent.systemPrompt())
                .state(agent.state())
                .turns(renumbered)
                .sessionId(agent.sessionId())
                .build();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private String buildSummary(List<TurnRecord> turns) {
        StringBuilder sb = new StringBuilder();
        for (var t : turns) {
            sb.append("Turn #")
                    .append(t.turnNumber())
                    .append(":\n")
                    .append(t.thought() != null ? t.thought() : "(no thought)")
                    .append("\n\n");
        }
        String history = sb.toString();

        String prompt =
                "You are a conversation summarizer. Summarize the following agent conversation turns. "
                        + "Focus on: decisions made, code written/changed, files modified, errors encountered, "
                        + "and unresolved questions. Be concise but thorough.\n\n"
                        + history;

        try {
            VetoResponse r =
                    caller.call(
                            new VetoRequest(
                                    "Summarize conversations accurately and concisely.",
                                    prompt,
                                    List.of(),
                                    ProviderType.DEEPSEEK,
                                    "deepseek-v4-pro",
                                    "deepseek-key",
                                    new LlmOptions(0.0, null, 1024, Duration.ofSeconds(60))));
            return r.thought();
        } catch (Exception e) {
            log.warn("Compaction summarization failed, using fallback", e);
            return "(summary unavailable — compaction skipped)";
        }
    }

    private String extractMemory(String summary) {
        // Simple accumulation for MVP. Future: use LLM to extract structured facts.
        if (summary.length() > 200) {
            return memory.isEmpty()
                    ? summary.substring(0, Math.min(summary.length(), 500))
                    : memory + "\n" + summary.substring(0, Math.min(summary.length(), 500));
        }
        return memory;
    }
}
