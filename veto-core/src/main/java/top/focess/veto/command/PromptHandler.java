package top.focess.veto.command;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

/**
 * The terminal transport facade for plain-text (non-slash) prompts ( "Unified Agent Execution
 * Pipeline").
 *
 * <p>The legacy blocking ReAct loop that used to live here is gone — loop execution is hosted
 * exclusively in {@code veto-core} on a virtual thread via {@link AgentService} / {@link
 * AgentRunner}. This facade now does only what a transport should:
 *
 * <ol>
 *   <li>Authenticates the caller via {@link CredentialVault} and rejects empty prompts.
 *   <li>Resolves the active LLM configuration (provider, model, system prompt) for the user into an
 *       {@link AgentRunner.LlmBinding}.
 *   <li>Delegates to {@link AgentService#submit(String, String, AgentRunner.LlmBinding, Duration,
 *       java.util.function.Consumer) AgentService.submit(...)}, forwarding each user-facing message
 *       the agent emits to {@link VetoCommandSender#output(String)} as it streams (the emission
 *       seam), then awaits the episode result.
 *   <li>Returns the synchronous {@link IpcFrame.TerminalResponse} — {@link IpcFrame.Done} on
 *       success, {@link IpcFrame.Error} on failure — per the closed-loop contract.
 * </ol>
 *
 * <p>Agent lifecycle (creation, eviction, termination) is owned by {@link AgentService}; this
 * facade only resolves config and streams. Per-user active-pattern / ad-hoc-config state stays here
 * (it is transport-layer session state, not agent state).
 *
 * <h3>Thread safety</h3>
 *
 * <p>{@link #activePatterns} / {@link #adhocConfigs} are {@link ConcurrentHashMap}. The agent map
 * lives in {@link AgentService}, which serializes per-agent episodes (the synchronous submit+await
 * blocks the dispatch worker for one terminal while the agent runs).
 */
public class PromptHandler {

    private static final Logger log = LoggerFactory.getLogger(PromptHandler.class);

    /** How long to block for one agent episode before timing the terminal out. */
    private static final Duration EPISODE_TIMEOUT = Duration.ofMinutes(5);

    /** Vault used to look up the currently authenticated user for each terminal. */
    private final CredentialVault vault;

    /** The shared agent service that owns loop execution + agent lifecycle. */
    private final AgentService agentService;

    /**
     * Per-user active LLM pattern name (key = username, value = pattern name). Shared with {@code
     * PatternCommand} via {@link CommandConfiguration}.
     */
    private final ConcurrentHashMap<String, String> activePatterns;

    private final ConcurrentHashMap<String, LlmConfig> adhocConfigs = new ConcurrentHashMap<>();

    /** Repository used to look up user-defined agent patterns by owner and name. */
    private final AgentPatternRepository patternRepo;

    /**
     * Constructs a new {@code PromptHandler}.
     *
     * @param vault the credential vault used to resolve the current logged-in user
     * @param agentService the shared agent service that owns loop execution + agent lifecycle
     * @param activePatterns the shared map of per-user active pattern names
     * @param patternRepo the repository for user-defined agent patterns
     */
    public PromptHandler(
            @NonNull CredentialVault vault,
            @NonNull AgentService agentService,
            @NonNull ConcurrentHashMap<String, String> activePatterns,
            @NonNull AgentPatternRepository patternRepo) {
        this.vault = vault;
        this.agentService = agentService;
        this.activePatterns = activePatterns;
        this.patternRepo = patternRepo;
    }

    /**
     * Returns a point-in-time snapshot of the live agent sessions (delegated to {@link
     * AgentService}).
     *
     * <p>Exposed for read-only inspection — building {@link IpcMeta#TURN_NUMBER} metadata in {@link
     * CommandRegistry} and the {@code /status} summary. Returns a snapshot so callers can iterate
     * without concurrent-modification risk; callers must not mutate the returned map.
     *
     * @return a snapshot of the live agents keyed by terminal ID; never {@code null}
     */
    public @NonNull Map<String, Agent> sessions() {
        Map<String, Agent> snapshot = new HashMap<>();
        agentService.agentsView().forEach(snapshot::put);
        return snapshot;
    }

    public void usePattern(String username, String patternName) {
        activePatterns.put(username, patternName);
        LlmConfig removed = adhocConfigs.remove(username);
        if (removed != null) {
            try {
                vault.delete(removed.credKey());
            } catch (Exception ignored) {
            }
        }
    }

    public void setAdhocConfig(
            String username,
            ProviderType provider,
            String model,
            String systemPrompt,
            String apiKey) {
        String credKey = "agent-adhoc-" + username;
        try {
            vault.store(credKey, apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store ad-hoc API key: " + e.getMessage(), e);
        }
        adhocConfigs.put(username, new LlmConfig(provider, model, credKey, systemPrompt));
        activePatterns.remove(username);
    }

    public void deactivateAgent(String username) {
        activePatterns.remove(username);
        LlmConfig removed = adhocConfigs.remove(username);
        if (removed != null) {
            try {
                vault.delete(removed.credKey());
            } catch (Exception ignored) {
            }
        }
    }

    public String getActiveAgentString(String username) {
        LlmConfig adhoc = adhocConfigs.get(username);
        if (adhoc != null) {
            return "Ad-hoc agent (" + adhoc.provider() + "/" + adhoc.model() + ")";
        }
        String pat = activePatterns.get(username);
        if (pat != null) {
            return "Pattern '" + pat + "'";
        }
        return "None";
    }

    /**
     * Handle a plain-text prompt by delegating to {@link AgentService}, streaming the agent's
     * user-facing messages to the sender as they are emitted, and returning the terminal frame.
     *
     * <p>Never returns {@code null} — always returns either {@link IpcFrame.Done} on success or
     * {@link IpcFrame.Error} on failure. The agent's user-facing messages are streamed as {@link
     * IpcFrame.Delta} frames via {@link VetoCommandSender#output(String)} while the episode runs;
     * the final {@link IpcFrame.Done} carries only session metadata (the text was already
     * streamed). On failure the message was not streamed, so the {@link IpcFrame.Error} carries it.
     *
     * <p><b>Contract — must not throw</b> for any recoverable failure: every {@link Exception}
     * (timeout, interruption, generic) is caught and returned as an {@link IpcFrame.Error}, so
     * callers can rely on always receiving a terminal frame without wrapping this call in their own
     * {@code try/catch}. Only an unrecoverable JVM {@link Error} could escape.
     */
    public IpcFrame.@NonNull TerminalResponse handle(
            @NonNull String prompt, @NonNull String terminalId, @NonNull VetoCommandSender sender) {
        String user = vault.getCurrentUser();
        if (user == null) {
            return IpcFrame.Error.ofError("Not logged in. Use /login.");
        }
        if (prompt.isEmpty()) {
            return IpcFrame.Error.ofError("Empty prompt.");
        }

        LlmConfig config = resolveLlmConfig(user);
        if (config == null) {
            return IpcFrame.Error.ofError(
                    "No agent is currently active. Use /agent use <patternName> to select an agent.");
        }

        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        config.provider(),
                        config.model(),
                        config.credKey(),
                        LlmOptions.defaults(),
                        config.systemPrompt());

        try {
            // Stream each user-facing message the agent emits ( seam) while the episode runs,
            // then block for the result. The sink is attached/detached inside AgentService.submit.
            AgentResult result =
                    agentService.submit(
                            terminalId, prompt, binding, EPISODE_TIMEOUT, sender::output);

            Map<String, Object> doneMeta = new HashMap<>();
            doneMeta.put(IpcMeta.USERNAME, user);
            doneMeta.put(IpcMeta.TURN_NUMBER, turnsOf(result));

            if (result.success()) {
                // The message text was already streamed as Delta frames; Done carries meta only.
                return new IpcFrame.Done(doneMeta, null);
            }
            // Failure (breaker trip / error): the reason was not streamed as an assistantResponse,
            // so surface it in the Error frame.
            String reason =
                    result.message() == null || result.message().isBlank()
                            ? "Agent failed."
                            : result.message();
            return IpcFrame.Error.ofError(reason);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Agent episode timed out for terminal {}", terminalId);
            return IpcFrame.Error.ofError("Agent timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IpcFrame.Error.ofError("Interrupted.");
        } catch (Exception e) {
            log.error("Prompt failed for terminal {}", terminalId, e);
            return IpcFrame.Error.ofError("Agent failed: " + e.getMessage());
        }
    }

    /**
     * Reads the episode's turn count from the result metadata ({@code turns}, set by the runner).
     */
    private static int turnsOf(AgentResult result) {
        if (result == null || result.metadata() == null) {
            return 0;
        }
        Object v = result.metadata().get("turns");
        return v instanceof Number n ? n.intValue() : 0;
    }

    // ── LLM config resolution ─────────────────────────────────────────────

    /** Resolved LLM configuration — either the active pattern or defaults. */
    public record LlmConfig(
            ProviderType provider, String model, String credKey, String systemPrompt) {}

    public String getActivePatternName(String username) {
        return activePatterns.get(username);
    }

    /** Looks up the active pattern for the user, falling back to built-in defaults. */
    public LlmConfig resolveLlmConfig(@NonNull String user) {
        LlmConfig adhoc = adhocConfigs.get(user);
        if (adhoc != null) {
            return adhoc;
        }

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
        return null;
    }

    // ── session removal ───────────────────────────────────────────────────

    /**
     * Removes the agent for the given terminal ID (delegated to {@link AgentService}).
     *
     * <p>Called by logout / cleanup paths (e.g. {@code LogoutCommand}) to free in-memory state when
     * a terminal disconnects or the user explicitly logs out.
     *
     * @param terminalId the ZMQ identity of the terminal whose agent should be removed
     */
    public void removeSession(@NonNull String terminalId) {
        agentService.remove(terminalId);
    }
}
