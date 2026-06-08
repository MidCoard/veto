package top.focess.veto.command;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionCompactor;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.llm.core.*;
import top.focess.veto.vault.CredentialVault;

/**
 * Handles plain-text prompts routed directly to the LLM agent. Uses the standard {@link
 * top.focess.command.CommandSender#output(String)} API to stream responses, and sets {@link
 * VetoCommandSender#doneMeta()} for session metadata.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final CredentialVault vault;
    private final UniformLLMCaller caller;
    private final ConcurrentHashMap<String, Agent> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCompactor> compactors =
            new ConcurrentHashMap<>();

    public PromptHandler(@NotNull CredentialVault vault, @NotNull UniformLLMCaller caller) {
        this.vault = vault;
        this.caller = caller;
    }

    @NotNull
    public Map<String, Agent> sessions() {
        return sessions;
    }

    /**
     * Handle a plain-text prompt and stream the LLM response via the sender's {@code output()}
     * method. On completion, sets metadata on the sender for the dispatch loop to include in the
     * terminal {@code Done} frame. On failure, sets the error flag on the sender.
     */
    public void handle(
            @NotNull String prompt, @NotNull String terminalId, @NotNull VetoCommandSender sender) {
        String user = vault.getCurrentUser();
        if (user == null) {
            sender.output("Not logged in. Use /login.");
            sender.setErrorFlag();
            return;
        }
        if (prompt.isBlank()) {
            sender.output("Empty prompt.");
            sender.setErrorFlag();
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

            String thought = r.thought();
            if (thought != null && !thought.isBlank()) {
                sender.output(thought);
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

            sender.doneMeta().put(IpcMeta.USERNAME, user);
            sender.doneMeta().put(IpcMeta.TURN_NUMBER, agent.turns().size());
        } catch (Exception e) {
            log.error("Prompt failed for terminal {}", terminalId, e);
            sender.output("LLM call failed: " + e.getMessage());
            sender.setErrorFlag();
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

    public void removeSession(@NotNull String terminalId) {
        sessions.remove(terminalId);
        compactors.remove(terminalId);
    }
}
