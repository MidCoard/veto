package top.focess.veto.command;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionCompactor;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.llm.core.*;
import top.focess.veto.vault.CredentialVault;

/**
 * Handles plain-text prompts routed directly to the LLM agent. No command prefix needed — any text
 * without a leading {@code /} is an implicit prompt.
 *
 * <p>Agent sessions are keyed by the terminal session token so that each login session maintains a
 * continuous conversation. Stale sessions (no activity for 30 minutes) are evicted on each request
 * to prevent unbounded memory growth under many terminal connections.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);

    /**
     * Evict sessions idle longer than this.
     */
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final CredentialVault vault;
    private final UniformLLMCaller caller;
    private final ConcurrentHashMap<String, Agent> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCompactor> compactors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activePatterns = new ConcurrentHashMap<>();

    public PromptHandler(CredentialVault vault, UniformLLMCaller caller) {
        this.vault = vault;
        this.caller = caller;
    }

    public Map<String, Agent> sessions() {
        return sessions;
    }

    public Map<String, String> activePatterns() {
        return activePatterns;
    }

    /**
     * Handle a plain-text prompt. Uses the terminal session token as the conversation key so that
     * turns accumulate within the same login session instead of starting a fresh agent every
     * prompt.
     */
    public TerminalResponse handle(String prompt, String sessionToken) {
        String user = vault.getCurrentUser();
        if (user == null) {
            return TerminalResponse.error("Not logged in. Use /login.");
        }
        if (prompt.isBlank()) return TerminalResponse.error("Empty prompt");

        // Evict stale sessions before creating/updating to bound memory
        evictStaleSessions();

        // Use the session token as the conversation key; fall back to a transient key
        String sid = sessionToken != null ? sessionToken : "anon-" + System.currentTimeMillis();

        ProviderType provider = ProviderType.DEEPSEEK;
        String model = "deepseek-v4-pro";
        String credKey = "deepseek-key";
        String sysPrompt = "You are a helpful coding assistant. Be concise.";

        Agent agent =
                sessions.computeIfAbsent(
                        sid,
                        k ->
                                Agent.builder()
                                        .name("agent-" + k.substring(0, Math.min(8, k.length())))
                                        .systemPrompt(sysPrompt)
                                        .sessionId(k)
                                        .build()
                                        .withState(AgentState.RUNNING));
        try {
            VetoResponse r =
                    caller.call(
                            new VetoRequest(
                                    agent.systemPrompt()
                                            + "\n\nRespond in JSON: {\"thought\":\"...\","
                                            + " \"call\":null, \"is_finished\":true}",
                                    prompt,
                                    List.of(),
                                    provider,
                                    model,
                                    credKey,
                                    new LlmOptions(0.0, null, 1024, Duration.ofSeconds(60))));

            agent =
                    agent.appendTurn(
                            new TurnRecord(
                                    agent.nextTurnNumber(), r.thought(), null, null, null, null));

            SessionCompactor compactor =
                    compactors.computeIfAbsent(sid, k -> new SessionCompactor(caller));
            if (compactor.shouldCompact(agent)) {
                agent = compactor.compact(agent);
            }
            sessions.put(sid, agent);
            return new TerminalResponse(
                    ResponseType.MESSAGE,
                    r.thought(),
                    Map.of("sessionId", sid, "turnNumber", agent.turns().size()));
        } catch (Exception e) {
            log.error("Prompt failed for session {}", sid, e);
            return TerminalResponse.error("LLM call failed: " + e.getMessage());
        }
    }

    /**
     * Remove sessions that have been idle for longer than {@link #SESSION_TTL_MS}. An agent is
     * considered idle if its last turn was recorded more than the TTL ago.
     */
    private void evictStaleSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        Iterator<Map.Entry<String, Agent>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Agent> entry = it.next();
            Agent agent = entry.getValue();
            if (agent.turns().isEmpty()) continue;
            TurnRecord lastTurn = agent.turns().get(agent.turns().size() - 1);
            if (lastTurn.timestamp().toEpochMilli() < cutoff) {
                log.debug("Evicting stale agent session {}", entry.getKey());
                it.remove();
                compactors.remove(entry.getKey());
            }
        }
    }

    /**
     * Drop a specific session (called on logout).
     */
    public void removeSession(String sessionToken) {
        sessions.remove(sessionToken);
        compactors.remove(sessionToken);
    }
}
