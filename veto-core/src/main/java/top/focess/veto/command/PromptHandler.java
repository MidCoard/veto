package top.focess.veto.command;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * The terminal transport facade for plain-text (non-slash) prompts.
 *
 * <p>Now a thin transport layer: it resolves the active session's LLM config via {@link
 * SessionService} and submits the prompt to {@link AgentService} keyed by <b>session id</b> (not
 * terminal id). All session/agent lifecycle state (active session, history replay, config
 * resolution) lives in {@link SessionService}; this class only routes and streams.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Stateless beyond its injected dependencies. The agent map lives in {@link AgentService} (which
 * serializes per-agent episodes); the active-session map lives in {@link SessionService}.
 */
public class PromptHandler {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.PromptHandler");

    /** How long to block for one agent episode before timing the terminal out. */
    private static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofMinutes(5);

    private final @NonNull KeysteadVault vault;
    private final @NonNull AgentService agentService;
    private final @NonNull SessionService sessionService;

    /**
     * Constructs a new {@code PromptHandler}.
     *
     * @param vault the credential vault used to resolve the current logged-in user
     * @param agentService the shared agent service that owns loop execution + agent lifecycle
     * @param sessionService the session service that owns the active-session map + config
     *     resolution
     */
    public PromptHandler(
            @NonNull KeysteadVault vault,
            @NonNull AgentService agentService,
            @NonNull SessionService sessionService) {
        this.vault = vault;
        this.agentService = agentService;
        this.sessionService = sessionService;
    }

    /**
     * Returns a point-in-time snapshot of the live agent sessions (delegated to {@link
     * AgentService}). Keyed by session id. Exposed for read-only inspection ({@code /status}, done
     * meta). Callers must not mutate the returned map.
     */
    public @NonNull Map<String, Agent> sessions() {
        return new HashMap<>(agentService.agentsView());
    }

    /** The active agent for the terminal (if any), for {@code /compact}. */
    public Agent activeAgent(@NonNull String terminalId) {
        return sessionService.activeAgent(terminalId).orElse(null);
    }

    /** The active session id for the terminal (if any), for done-meta / status. */
    public String activeSession(@NonNull String terminalId) {
        return sessionService.activeSession(terminalId).orElse(null);
    }

    /** Detach the terminal from its session (called by {@code /logout}). */
    public void deactivate(@NonNull String terminalId) {
        sessionService.deactivate(terminalId);
    }

    /**
     * Detach every terminal attached to one of {@code username}'s sessions (called by the unified
     * logout path). Sessions persist in the DB and can be re-activated on re-login.
     */
    public void deactivateUser(@NonNull String username) {
        sessionService.deactivateUser(username);
    }

    /**
     * Handle a plain-text prompt by delegating to {@link AgentService}, streaming the agent's
     * user-facing messages to the sender as they are emitted, and returning the terminal frame.
     *
     * <p>Never returns {@code null} - always {@link IpcFrame.Done} on success or {@link
     * IpcFrame.Error} on failure. Must not throw for any recoverable failure (every {@code
     * Exception} is caught and returned as an {@link IpcFrame.Error}).
     */
    public IpcFrame.@NonNull TerminalResponse handle(
            @NonNull String prompt, @NonNull String terminalId, @NonNull VetoCommandSender sender) {
        String user = vault.currentUser();
        if (user == null) {
            return IpcFrame.Error.ofError("Not logged in. Use /login.");
        }
        if (prompt.isEmpty()) {
            return IpcFrame.Error.ofError("Empty prompt.");
        }

        Optional<LlmConfig> opt = sessionService.resolveLlmConfig(terminalId);
        if (opt.isEmpty()) {
            // Server restart or fresh reconnect wiped the in-memory active-session map: auto-resume
            // the user's most-recently-active session IN THE TERMINAL'S WORKSPACE (replays durable
            // history, continues seamlessly). Sessions bound to a different workspace are skipped
            // so a terminal never silently resumes into a session that operates elsewhere.
            opt = sessionService.resumeLastSession(terminalId, user, sender.cwd());
        }
        if (opt.isEmpty()) {
            return IpcFrame.Error.ofError(
                    "No active session in this workspace. Use /session create <pattern> or"
                            + " /session activate <name>.");
        }
        LlmConfig config = opt.get();
        String sessionId = sessionService.activeSession(terminalId).orElseThrow();

        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        config.provider(),
                        config.model(),
                        config.credKey(),
                        LlmOptions.defaults(),
                        null, // systemPromptBase - persona-derived in PromptCompiler
                        config.baseUrl());

        try {
            // Stream each user-facing message the agent emits while the episode runs, then block
            // for
            // the result. The sink is attached/detached inside AgentService.submit.
            AgentResult result =
                    agentService.submit(
                            sessionId,
                            prompt,
                            binding,
                            EPISODE_TIMEOUT,
                            sender::output,
                            sender::sendVetoPrompt,
                            sender::outputThought,
                            sender::sendToolCall,
                            sender::sendToolResult);

            Map<String, Object> doneMeta = new HashMap<>();
            doneMeta.put(IpcMeta.USERNAME, user);
            doneMeta.put(IpcMeta.TURN_NUMBER, turnsOf(result));

            if (result.success()) {
                // The message text was already streamed as Delta frames; Done carries meta only.
                return new IpcFrame.Done(doneMeta, null);
            }
            // Failure (breaker trip / error): the reason was not streamed, so surface it in Error.
            String resultMessage = result.message();
            String reason = resultMessage.isBlank() ? "Agent failed." : resultMessage;
            return IpcFrame.Error.ofError(reason);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Agent episode timed out for session {}", sessionId);
            return IpcFrame.Error.ofError("Agent timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IpcFrame.Error.ofError("Interrupted.");
        } catch (Exception e) {
            log.error("Prompt failed for session {}", sessionId, e);
            return IpcFrame.Error.ofError("Agent failed: " + e.getMessage());
        }
    }

    /**
     * Reads the episode's turn count from the result metadata ({@code turns}, set by the runner).
     */
    private static int turnsOf(@NonNull AgentResult result) {
        Object v = result.metadata().get("turns");
        return v instanceof Number n ? n.intValue() : 0;
    }
}
