package top.focess.veto.command.commands;

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
import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.vault.CredentialVault;

public class SendCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SendCommand.class);

    private final CredentialVault vault;
    private final UniformLLMCaller caller;
    private final ConcurrentHashMap<String, Agent> sessions;
    private final ConcurrentHashMap<String, SessionCompactor> compactors;
    private final ConcurrentHashMap<String, String> activePatterns;

    public SendCommand(
            CredentialVault vault,
            UniformLLMCaller caller,
            ConcurrentHashMap<String, Agent> sessions,
            ConcurrentHashMap<String, SessionCompactor> compactors,
            ConcurrentHashMap<String, String> activePatterns) {
        this.vault = vault;
        this.caller = caller;
        this.sessions = sessions;
        this.compactors = compactors;
        this.activePatterns = activePatterns;
    }

    @Override
    public String name() {
        return "send";
    }

    @Override
    public String description() {
        return "Send a prompt to the agent";
    }

    @Override
    public String usage() {
        return "send <message>";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of(new ArgDef("message", "string", true, "The prompt to send"));
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        String user = vault.getCurrentUser();
        if (user == null)
            return TerminalResponse.error("Not logged in. Use: login <username> <password>");

        String message = (String) args.getOrDefault("prompt", "");
        if (message.isBlank()) return TerminalResponse.error("Usage: send <message>");

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
                                        .name("terminal-agent-" + k.substring(0, 8))
                                        .systemPrompt(sysPrompt)
                                        .sessionId(k)
                                        .build()
                                        .withState(AgentState.RUNNING));

        try {
            VetoResponse r =
                    caller.call(
                            new VetoRequest(
                                    agent.systemPrompt()
                                            + "\n\nRespond in JSON:"
                                            + " {\"thought\":\"...\", \"call\":null,"
                                            + " \"is_finished\":true}",
                                    message,
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
                log.info(
                        "Compacting session {} ({} turns)",
                        sid.substring(0, 8),
                        agent.turns().size());
                agent = compactor.compact(agent);
            }
            sessions.put(sid, agent);

            return new TerminalResponse(
                    ResponseType.MESSAGE,
                    r.thought(),
                    Map.of("sessionId", sid, "turnNumber", agent.turns().size()));
        } catch (Exception e) {
            log.error("Send failed", e);
            return TerminalResponse.error("LLM call failed: " + e.getMessage());
        }
    }
}
