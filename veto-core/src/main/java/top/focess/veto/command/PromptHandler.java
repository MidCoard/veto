package top.focess.veto.command;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * without a leading / is an implicit prompt.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);

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

    public TerminalResponse handle(String prompt, String sessionToken) {
        String user = vault.getCurrentUser();
        if (user == null) {
            return TerminalResponse.error("Not logged in. Use /login.");
        }
        if (prompt.isBlank()) return TerminalResponse.error("Empty prompt");

        String sid = UUID.randomUUID().toString();
        ProviderType provider = ProviderType.DEEPSEEK;
        String model = "deepseek-v4-pro";
        String credKey = "deepseek-key";
        String sysPrompt = "You are a helpful coding assistant. Be concise.";

        Agent agent =
                sessions.computeIfAbsent(
                        sid,
                        k ->
                                Agent.builder()
                                        .name("agent-" + k.substring(0, 8))
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
            log.error("Prompt failed", e);
            return TerminalResponse.error("LLM call failed: " + e.getMessage());
        }
    }
}
