package top.focess.veto.command;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionCompactor;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.llm.core.*;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

/**
 * Handles plain-text prompts routed directly to the LLM agent. Uses the standard {@link
 * top.focess.command.CommandSender#output(String)} API to stream responses, and sets {@link
 * VetoCommandSender#doneMeta()} for session metadata.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_TOOL_LOOP_ITERATIONS = 10;

    private final CredentialVault vault;
    private final UniformLLMCaller caller;
    private final ConcurrentHashMap<String, Agent> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCompactor> compactors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activePatterns;
    private final AgentPatternRepository patternRepo;

    public PromptHandler(
            @NotNull CredentialVault vault,
            @NotNull UniformLLMCaller caller,
            @NotNull ConcurrentHashMap<String, String> activePatterns,
            @NotNull AgentPatternRepository patternRepo) {
        this.vault = vault;
        this.caller = caller;
        this.activePatterns = activePatterns;
        this.patternRepo = patternRepo;
    }

    @NotNull
    public Map<String, Agent> sessions() {
        return sessions;
    }

    /**
     * Handle a plain-text prompt and stream the LLM response via the sender's {@code output()}
     * method. On completion, returns a Done or Error response.
     */
    @Nullable
    public IpcFrame.TerminalResponse handle(
            @NotNull String prompt, @NotNull String terminalId, @NotNull VetoCommandSender sender) {
        String user = vault.getCurrentUser();
        if (user == null) {
            sender.output("Not logged in. Use /login.");
            return IpcFrame.Error.ofError("Not logged in. Use /login.");
        }
        if (prompt.isEmpty()) {
            sender.output("Empty prompt.");
            return IpcFrame.Error.ofError("Empty prompt.");
        }

        evictStaleSessions();

        LlmConfig config = resolveLlmConfig(user);

        var resultHolder = new IpcFrame.TerminalResponse[1];

        // Atomic read-modify-write — serializes concurrent prompts for the
        // same terminal while leaving other terminals unaffected.
        sessions.compute(
                terminalId,
                (k, agent) -> {
                    if (agent == null) {
                        agent =
                                Agent.builder()
                                        .name("agent-" + k.substring(0, Math.min(8, k.length())))
                                        .systemPrompt(config.systemPrompt)
                                        .sessionId(k)
                                        .build()
                                        .withState(AgentState.RUNNING);
                    }
                    try {
                        List<ToolDefinition> tools = resolveTools(agent);
                        List<TurnRecord> newTurns = new ArrayList<>();
                        String nextPrompt = prompt;

                        // ReAct loop: call LLM → process tool call → feed
                        // observation → repeat until finished or max iters
                        for (int iter = 0; iter < MAX_TOOL_LOOP_ITERATIONS; iter++) {
                            VetoResponse r =
                                    caller.call(
                                            new VetoRequest(
                                                    config.systemPrompt,
                                                    nextPrompt,
                                                    tools,
                                                    config.provider,
                                                    config.model,
                                                    config.credKey,
                                                    new LlmOptions(
                                                            0.0,
                                                            null,
                                                            1024,
                                                            Duration.ofSeconds(60))));

                            if (r.thought() != null && !r.thought().isBlank()) {
                                sender.output(r.thought());
                            }

                            String observation = null;
                            if (r.call() != null) {
                                observation = executeToolCall(r.call());
                                if (observation != null) {
                                    sender.output(
                                            "\n[tool:" + r.call().toolName() + "] " + observation);
                                }
                            }

                            newTurns.add(
                                    new TurnRecord(
                                            agent.nextTurnNumber() + newTurns.size(),
                                            r.thought(),
                                            r.call() != null ? r.call().toolName() : null,
                                            r.call() != null ? r.call().args() : null,
                                            observation,
                                            null));

                            if (r.isFinished() || r.call() == null) break;

                            nextPrompt =
                                    "Observation: "
                                            + (observation != null
                                                    ? observation
                                                    : "(tool executed)");
                        }

                        for (TurnRecord t : newTurns) {
                            agent = agent.appendTurn(t);
                        }

                        SessionCompactor compactor =
                                compactors.computeIfAbsent(
                                        terminalId, key -> new SessionCompactor(caller));
                        if (compactor.shouldCompact(agent)) {
                            agent =
                                    compactor.compact(
                                            agent, config.provider, config.model, config.credKey);
                        }
                        Map<String, Object> doneMeta = new HashMap<>();
                        doneMeta.put(IpcMeta.USERNAME, user);
                        doneMeta.put(IpcMeta.TURN_NUMBER, agent.turns().size());
                        resultHolder[0] = new IpcFrame.Done(doneMeta, null);
                    } catch (Exception e) {
                        log.error("Prompt failed for terminal {}", terminalId, e);
                        sender.output("LLM call failed: " + e.getMessage());
                        resultHolder[0] =
                                IpcFrame.Error.ofError("LLM call failed: " + e.getMessage());
                    }
                    return agent;
                });
        return resultHolder[0];
    }

    // ── LLM config resolution ─────────────────────────────────────────────

    /** Resolved LLM configuration — either the active pattern or defaults. */
    private record LlmConfig(
            ProviderType provider, String model, String credKey, String systemPrompt) {}

    /** Looks up the active pattern for the user, falling back to built-in defaults. */
    private LlmConfig resolveLlmConfig(@NotNull String user) {
        String patternName = activePatterns.get(user);
        if (patternName != null) {
            List<AgentPatternEntity> patterns = patternRepo.findByOwner(user);
            for (AgentPatternEntity p : patterns) {
                if (p.getName().equals(patternName)) {
                    try {
                        return new LlmConfig(
                                ProviderType.valueOf(p.getProvider()),
                                p.getModel(),
                                p.getCredentialKey(),
                                p.getSystemPrompt());
                    } catch (IllegalArgumentException e) {
                        log.warn(
                                "Unknown provider '{}' in pattern '{}', falling back",
                                p.getProvider(),
                                patternName);
                    }
                }
            }
        }
        return new LlmConfig(
                ProviderType.DEEPSEEK,
                "deepseek-v4-pro",
                "deepseek-key",
                "You are a helpful coding assistant. Be concise.");
    }

    // ── tool loop ─────────────────────────────────────────────────────────

    /** Resolve tools available to the agent. Currently returns an empty list — extend here. */
    private List<ToolDefinition> resolveTools(Agent agent) {
        return List.of();
    }

    /**
     * Execute a tool call and return the observation. Override or inject a {@code ToolExecutor}
     * bean to enable actual tool execution. Returns {@code null} when no executor is configured.
     */
    private String executeToolCall(ToolCall call) {
        return null;
    }

    // ── session eviction ──────────────────────────────────────────────────

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
