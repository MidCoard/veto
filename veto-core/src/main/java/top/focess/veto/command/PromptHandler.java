package top.focess.veto.command;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
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
 * Handles plain-text (non-slash) prompts by routing them through the LLM ReAct loop.
 *
 * <p>Each call to {@link #handle(String, String, VetoCommandSender)} runs the following pipeline:
 *
 * <ol>
 *   <li>Authenticates the caller via {@link top.focess.veto.vault.CredentialVault}.
 *   <li>Evicts stale agent sessions older than {@code SESSION_TTL_MS}.
 *   <li>Resolves the active LLM configuration (provider, model, system prompt) for the user.
 *   <li>Executes a ReAct (Reason + Act) loop: calls the LLM, processes any tool calls, feeds the
 *       observation back, and repeats until the model signals completion or the maximum iteration
 *       count is reached.
 *   <li>Appends the new turns to the in-memory {@link top.focess.veto.agent.Agent} and optionally
 *       compacts the session via {@link top.focess.veto.agent.SessionCompactor}.
 *   <li>Streams output deltas back to the terminal via {@link
 *       top.focess.command.CommandSender#output(String)}.
 * </ol>
 *
 * <h3>Thread safety</h3>
 *
 * <p>The {@link #sessions} and {@link #compactors} maps are {@link
 * java.util.concurrent.ConcurrentHashMap}. Per-session state is protected by {@link
 * ConcurrentHashMap#compute}, which serializes concurrent prompts for the same terminal while
 * leaving other terminals unaffected.
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);

    /** Idle sessions older than this threshold are removed by {@link #evictStaleSessions()}. */
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();

    /** Maximum number of LLM–tool-call iterations per prompt to prevent infinite loops. */
    private static final int MAX_TOOL_LOOP_ITERATIONS = 10;

    /** Vault used to look up the currently authenticated user for each terminal. */
    private final CredentialVault vault;

    /** Unified LLM caller that abstracts over multiple AI providers. */
    private final UniformLLMCaller caller;

    /** Live agent sessions keyed by terminal ID. Protected by {@link ConcurrentHashMap#compute}. */
    private final ConcurrentHashMap<String, Agent> sessions = new ConcurrentHashMap<>();

    /** Session compactors keyed by terminal ID; lazily created per terminal. */
    private final ConcurrentHashMap<String, SessionCompactor> compactors =
            new ConcurrentHashMap<>();

    /**
     * Per-user active LLM pattern name (key = username, value = pattern name). Shared with {@code
     * PatternCommand} via {@link CommandConfiguration}.
     */
    private final ConcurrentHashMap<String, String> activePatterns;

    /** Repository used to look up user-defined agent patterns by owner and name. */
    private final AgentPatternRepository patternRepo;

    /**
     * Constructs a new {@code PromptHandler}.
     *
     * @param vault the credential vault used to resolve the current logged-in user
     * @param caller the unified LLM caller used to invoke the AI model
     * @param activePatterns the shared map of per-user active pattern names
     * @param patternRepo the repository for user-defined agent patterns
     */
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

    /**
     * Returns the live agent session map.
     *
     * <p>Exposed for read-only inspection (e.g. building {@link IpcMeta#TURN_NUMBER} metadata in
     * {@link CommandRegistry}). Callers must not mutate the returned map directly.
     *
     * @return the live sessions map keyed by terminal ID; never {@code null}
     */
    @NotNull
    public Map<String, Agent> sessions() {
        return sessions;
    }

    /**
     * Handle a plain-text prompt and stream the LLM response via the sender's {@code output()}
     * method. On completion, returns a Done or Error response.
     *
     * <p>Never returns {@code null} — always returns either {@link IpcFrame.Done} on success or
     * {@link IpcFrame.Error} on failure. Error frames carry the message themselves and are rendered
     * by the terminal, so {@code sender.output()} must not be called redundantly before returning
     * an error.
     */
    @NotNull
    public IpcFrame.TerminalResponse handle(
            @NotNull String prompt, @NotNull String terminalId, @NotNull VetoCommandSender sender) {
        String user = vault.getCurrentUser();
        if (user == null) {
            return IpcFrame.Error.ofError("Not logged in. Use /login.");
        }
        if (prompt.isEmpty()) {
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

    /**
     * Removes agent sessions whose most-recent turn is older than {@link #SESSION_TTL_MS}.
     *
     * <p>Called at the start of each {@link #handle} invocation to bound memory growth. Sessions
     * with no turns are never evicted (the session has not yet produced any output).
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
     * Removes the agent session and compactor for the given terminal ID.
     *
     * <p>Called by logout or cleanup paths (e.g. {@code LogoutCommand}) to free in-memory state
     * when a terminal disconnects or the user explicitly logs out.
     *
     * @param terminalId the ZMQ identity of the terminal whose session should be removed
     */
    public void removeSession(@NotNull String terminalId) {
        sessions.remove(terminalId);
        compactors.remove(terminalId);
    }
}
