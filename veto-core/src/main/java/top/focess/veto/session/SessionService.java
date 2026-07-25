package top.focess.veto.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

/**
 * Owns the session lifecycle: create/list/activate/deactivate, plus the per-terminal active-session
 * map and LLM-config resolution. An interface (terminal or web frontend) attaches to one active
 * session; the session's primary agent is get-or-created in {@link AgentService} with replayed
 * history loaded by {@link SessionHistoryLoader}.
 *
 * <p>Per-user identity for memory capture is not yet wired here (the placeholder {@code
 * DEFAULT_USER_ID} is used, matching the existing transport path); multi-user isolation is the
 * follow-up tracked on {@link AgentService}.
 */
@Service
public class SessionService {

    /** Placeholder until per-user identity is threaded from the transport (see class javadoc). */
    private static final UUID DEFAULT_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final SessionRepository sessions;
    private final AgentInstanceRepository agents;
    private final AgentPatternRepository patterns;
    private final AgentService agentService;
    private final SessionHistoryLoader historyLoader;

    /** Per-terminal active session id. Key = terminal id, value = session id. */
    private final ConcurrentHashMap<String, String> activeSessions = new ConcurrentHashMap<>();

    public SessionService(
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository agents,
            @NonNull AgentPatternRepository patterns,
            @NonNull AgentService agentService,
            @NonNull SessionHistoryLoader historyLoader) {
        this.sessions = sessions;
        this.agents = agents;
        this.patterns = patterns;
        this.agentService = agentService;
        this.historyLoader = historyLoader;
    }

    /**
     * Creates a session + its primary agent from a pattern, named after the pattern. Does NOT
     * auto-activate. Equivalent to {@code createSession(owner, patternName, null,
     * System.getProperty("user.dir"))} - the session's workspace defaults to the JVM working dir.
     */
    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner, @NonNull String patternName) {
        return createSession(owner, patternName, null, System.getProperty("user.dir"));
    }

    /**
     * Creates a session + its primary agent from a pattern. Does NOT auto-activate. The session's
     * workspace defaults to the JVM working dir.
     *
     * @param owner the session owner
     * @param patternName the pattern to instantiate the primary agent from
     * @param sessionName the desired session name; null/empty defaults to {@code patternName}
     */
    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner, @NonNull String patternName, @Nullable String sessionName) {
        return createSession(owner, patternName, sessionName, System.getProperty("user.dir"));
    }

    /**
     * Creates a session + its primary agent from a pattern with the session's workspace. Does NOT
     * auto-activate. The {@code workspaceRoots} (CSV of host paths) is persisted on the session so
     * every agent the session spawns resolves paths against these roots. Never {@code null} - the
     * terminal path supplies its cwd and a remote UI must declare roots explicitly; a blank value
     * falls back to the JVM working dir at activation.
     *
     * @param owner the session owner
     * @param patternName the pattern to instantiate the primary agent from
     * @param sessionName the desired session name; null/empty defaults to {@code patternName}
     * @param workspaceRoots CSV of host paths backing the session's workspace; never {@code null}
     */
    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner,
            @NonNull String patternName,
            @Nullable String sessionName,
            @NonNull String workspaceRoots) {
        AgentPatternEntity pattern =
                patterns.findByNameAndOwner(patternName, owner)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Pattern not found: " + patternName));

        String resolvedName =
                (sessionName == null || sessionName.isEmpty()) ? patternName : sessionName;
        if (sessions.findByNameAndOwner(resolvedName, owner).isPresent()) {
            throw new IllegalArgumentException(
                    "Session name '" + resolvedName + "' already exists; provide a unique name");
        }

        SessionEntity session =
                sessions.save(new SessionEntity(owner, resolvedName, workspaceRoots));
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        pattern.getId(),
                        AgentEntity.Role.PRIMARY,
                        resolvedName,
                        pattern.getProvider(),
                        pattern.getModel(),
                        pattern.getCredentialKey());
        agent = agents.save(agent);
        session.setPrimaryAgentId(agent.getId());
        return sessions.save(session);
    }

    public @NonNull List<SessionEntity> listSessions(@NonNull String owner) {
        return sessions.findByOwner(owner);
    }

    /**
     * Attaches the terminal to an existing session. Get-or-creates the primary agent (seeding
     * replayed history) and returns its LLM config. Sets the terminal's active session on success.
     */
    @Transactional
    public @NonNull Optional<LlmConfig> activate(
            @NonNull String terminalId, @NonNull String sessionName, @NonNull String owner) {
        SessionEntity session =
                sessions.findByNameAndOwner(sessionName, owner)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Session not found: " + sessionName));
        AgentEntity agent = primaryAgent(session);
        if (agent == null) {
            return Optional.empty();
        }

        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        ProviderType.valueOf(agent.getProvider()),
                        agent.getModel(),
                        agent.getCredentialKey(),
                        LlmOptions.defaults(),
                        null);
        List<TurnRecord> history = historyLoader.load(session.getId());
        agentService.getOrCreateAgent(
                session.getId(), binding, history, DEFAULT_USER_ID, session.getWorkspaceRoots());
        session.touch();
        sessions.save(session);
        activeSessions.put(terminalId, session.getId());

        return Optional.of(
                new LlmConfig(
                        ProviderType.valueOf(agent.getProvider()),
                        agent.getModel(),
                        agent.getCredentialKey()));
    }

    /**
     * Auto-resumes the owner's most-recently-active session after a server restart or terminal
     * reconnect (the in-memory active-session map is wiped on restart). Replays durable history by
     * delegating to {@link #activate}. Returns empty if the owner has no sessions or the last one
     * has no primary agent.
     */
    @Transactional
    public @NonNull Optional<LlmConfig> resumeLastSession(
            @NonNull String terminalId, @NonNull String owner) {
        return sessions.findFirstByOwnerOrderByLastActiveAtDesc(owner)
                .flatMap(session -> activate(terminalId, session.getName(), owner));
    }

    public void deactivate(@NonNull String terminalId) {
        activeSessions.remove(terminalId);
    }

    /**
     * Detaches every terminal currently attached to one of {@code username}'s sessions. Used by the
     * unified logout path ({@code AuthLifecycleManager}) - the sessions themselves persist in the
     * DB and can be re-activated on re-login, but no terminal remains attached.
     */
    public void deactivateUser(@NonNull String username) {
        activeSessions
                .entrySet()
                .removeIf(
                        e -> {
                            SessionEntity session = sessions.findById(e.getValue()).orElse(null);
                            return session == null || username.equals(session.getOwner());
                        });
    }

    public @NonNull Optional<String> activeSession(@NonNull String terminalId) {
        return Optional.ofNullable(activeSessions.get(terminalId));
    }

    public @NonNull Optional<LlmConfig> resolveLlmConfig(@NonNull String terminalId) {
        String sessionId = activeSessions.get(terminalId);
        if (sessionId == null) return Optional.empty();
        SessionEntity session = sessions.findById(sessionId).orElse(null);
        if (session == null) return Optional.empty();
        AgentEntity agent = primaryAgent(session);
        if (agent == null) return Optional.empty();
        return Optional.of(
                new LlmConfig(
                        ProviderType.valueOf(agent.getProvider()),
                        agent.getModel(),
                        agent.getCredentialKey()));
    }

    public @NonNull Optional<Agent> activeAgent(@NonNull String terminalId) {
        String sessionId = activeSessions.get(terminalId);
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(agentService.agentsView().get(sessionId));
    }

    private @Nullable AgentEntity primaryAgent(@NonNull SessionEntity session) {
        if (session.getPrimaryAgentId() == null) return null;
        return agents.findById(session.getPrimaryAgentId()).orElse(null);
    }
}
