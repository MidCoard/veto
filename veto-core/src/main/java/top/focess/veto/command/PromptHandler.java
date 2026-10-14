package top.focess.veto.command;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
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
 * <p>Stateless beyond its injected dependencies. Live membership belongs to the session agent
 * registry; each agent serializes its own episodes. The active-session map belongs to {@link
 * SessionService}.
 */
public class PromptHandler {

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
     * AgentService}). Keyed by session id. Exposed for read-only inspection ({@code /status},
     * terminal display). Callers must not mutate the returned map.
     */
    public @NonNull Map<String, Agent> sessions() {
        return Map.copyOf(agentService.agentsView());
    }

    /** The active agent for the terminal (if any), for {@code /compact}. */
    public Agent activeAgent(@NonNull String terminalId) {
        return sessionService.activeAgent(terminalId).orElse(null);
    }

    /** The active session id for the terminal (if any), for terminal display / status. */
    public String activeSession(@NonNull String terminalId) {
        return sessionService.activeSession(terminalId).orElse(null);
    }

    /** Detach the terminal from its session (called by {@code /logout}). */
    public void deactivate(@NonNull String terminalId) {
        sessionService.deactivate(terminalId);
    }

    /**
     * Detach every terminal attached to one of the user's sessions (called by the unified logout
     * path). Sessions persist in the DB and can be re-activated on re-login.
     */
    public void deactivateUser(@NonNull UUID userId) {
        sessionService.deactivateUser(userId);
    }

    /** Prepares a terminal prompt and lets its connection follow the admitted request handle. */
    public Frame.@NonNull TurnOutcome handle(
            @NonNull String prompt, @NonNull String terminalId, @NonNull VetoCommandSender sender) {
        UUID user = sender.userId();
        if (user != null && !vault.isUnlocked(user)) user = null;
        if (user == null) {
            return Frame.Error.ofError("Not logged in. Use /login.");
        }
        if (prompt.isEmpty()) {
            return Frame.Error.ofError("Empty prompt.");
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
            return Frame.Error.ofError(
                    "No active session in this workspace. Use /session create <pattern> or"
                            + " /session activate <name>.");
        }
        LlmConfig config = opt.get();
        String sessionId = sessionService.activeSession(terminalId).orElseThrow();

        LlmBinding binding =
                new LlmBinding(
                        config.provider(),
                        config.model(),
                        config.credKey(),
                        config.options(),
                        config.baseUrl());

        var owner = user;
        var session = sessionService.runtimeSession(sessionId, owner);
        var agent = session.primary(owner);
        var connection = sender.connection();
        var locale = connection.locale();
        connection.submitAgent(
                session,
                new Scope.AgentScope(owner, sessionId, agent.id()),
                admission -> session.submit(owner, prompt, binding, locale, admission));
        // Command dispatch returns immediately; the connection settles the bound agent request.
        return new Frame.Done(new Scope.SessionScope(owner, sessionId), null, -1, null, null, 0);
    }
}
