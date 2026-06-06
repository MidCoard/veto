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
import top.focess.veto.llm.core.*;
import top.focess.veto.vault.CredentialVault;

/**
 * Handles plain-text prompts routed directly to the LLM agent. Writes streaming deltas via {@link
 * VetoCommandSender}.
 *
 * <p>Agent sessions are keyed by terminal ID (from the IPC filename) so that turns accumulate
 * within the same terminal session.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);

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
     * Handle a plain-text prompt and stream the response via {@code io}. The I/O handler's {@code
     * delta()} is called for each chunk of the LLM response, followed by {@code done()} on
     * completion or {@code error()} on failure.
     */
    public void handle(String prompt, String terminalId, VetoCommandSender sender) {
        String user = vault.getCurrentUser();
        if (user == null) {
            sender.error("Not logged in. Use /login.");
            return;
        }
        if (prompt.isBlank()) {
            sender.error("Empty prompt");
            return;
        }

        evictStaleSessions();

        ProviderType provider = ProviderType.DEEPSEEK;
        String model = "deepseek-v4-pro";
        String credKey = "deepseek-key";
        String sysPrompt = "You are a helpful coding assistant. Be concise.";

        Agent agent =
                sessions.computeIfAbsent(
                        terminalId,
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

            // Stream the thought as deltas
            String thought = r.thought();
            if (thought != null && !thought.isBlank()) {
                // Split into sentence-chunks for a streaming feel
                sender.delta(thought);
            }

            agent =
                    agent.appendTurn(
                            new TurnRecord(
                                    agent.nextTurnNumber(), r.thought(), null, null, null, null));

            SessionCompactor compactor =
                    compactors.computeIfAbsent(terminalId, k -> new SessionCompactor(caller));
            if (compactor.shouldCompact(agent)) {
                agent = compactor.compact(agent);
            }
            sessions.put(terminalId, agent);

            sender.done(Map.of("username", user, "turnNumber", agent.turns().size()));
        } catch (Exception e) {
            log.error("Prompt failed for terminal {}", terminalId, e);
            sender.error("LLM call failed: " + e.getMessage());
        }
    }

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

    public void removeSession(String terminalId) {
        sessions.remove(terminalId);
        compactors.remove(terminalId);
    }
}
