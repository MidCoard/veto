package top.focess.veto.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * Owns the session lifecycle: create/list/activate/deactivate, plus the per-terminal active-session
 * map and LLM-config resolution. An interface (terminal or web frontend) attaches to one active
 * session; the session's primary agent is get-or-created in {@link AgentService} with replayed
 * history loaded by {@link SessionHistoryLoader}.
 *
 * <p>Per-user identity for memory capture is derived from the session owner via {@link
 * AgentService#userIdForOwner(String)}, so memories and turn logs attribute to the real user rather
 * than a shared placeholder.
 */
@Service
public class SessionService {

    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull AgentPatternRepository patterns;
    private final @NonNull AgentService agentService;
    private final @NonNull SessionHistoryLoader historyLoader;
    private final @NonNull ModelTierRegistry tierRegistry;
    private final @NonNull WorkspaceAdmissionPolicy workspaceAdmissionPolicy;

    /** Per-terminal active session id. Key = terminal id, value = session id. */
    private final @NonNull ConcurrentHashMap<String, String> activeSessions =
            new ConcurrentHashMap<>();

    @Autowired
    public SessionService(
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository agents,
            @NonNull AgentPatternRepository patterns,
            @NonNull AgentService agentService,
            @NonNull SessionHistoryLoader historyLoader,
            @NonNull ModelTierRegistry tierRegistry,
            @NonNull WorkspaceAdmissionPolicy workspaceAdmissionPolicy) {
        this.sessions = sessions;
        this.agents = agents;
        this.patterns = patterns;
        this.agentService = agentService;
        this.historyLoader = historyLoader;
        this.tierRegistry = tierRegistry;
        this.workspaceAdmissionPolicy = workspaceAdmissionPolicy;
    }

    /** Test/embedded compatibility constructor; Spring always supplies the admission policy. */
    public SessionService(
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository agents,
            @NonNull AgentPatternRepository patterns,
            @NonNull AgentService agentService,
            @NonNull SessionHistoryLoader historyLoader,
            @NonNull ModelTierRegistry tierRegistry) {
        this(
                sessions,
                agents,
                patterns,
                agentService,
                historyLoader,
                tierRegistry,
                WorkspaceAdmissionPolicy.unrestricted());
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
            @NonNull String owner, @NonNull String patternName, String sessionName) {
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
     * @param sessionName the desired session name; null/empty triggers an auto-generated name of
     *     the form {@code <patternName>-xxxxxxxx} (8 lowercase hex digits) that is unique within
     *     this workspace. An explicit name is still validated for workspace-scoped uniqueness.
     * @param workspaceRoots CSV of host paths backing the session's workspace; never {@code null}
     */
    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner,
            @NonNull String patternName,
            String sessionName,
            @NonNull String workspaceRoots) {
        return createSession(
                owner, patternName, sessionName, workspaceRoots, ToolResultPresentationMode.BASIC);
    }

    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner,
            @NonNull String patternName,
            String sessionName,
            @NonNull String workspaceRoots,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        return createSession(
                owner, patternName, sessionName, workspaceRoots, 0, toolResultPresentation);
    }

    @Transactional
    public @NonNull SessionEntity createSession(
            @NonNull String owner,
            @NonNull String patternName,
            String sessionName,
            @NonNull String workspaceRoots,
            int currentWorkspaceRootIndex,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        AgentPatternEntity pattern =
                patterns.findByNameAndOwner(patternName, owner)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                Msg.get(
                                                        "error.session.patternNotFound",
                                                        patternName)));

        // The workspace roots must exist before the agent can operate in them - create missing
        // directories up front so the model never has to mkdir its own workspace (observed live:
        // a model burning a turn on `cmd /c if not exist ... mkdir ...` for a fresh root).
        List<Path> declaredRoots;
        try {
            declaredRoots = workspaceAdmissionPolicy.admit(owner, workspaceRoots);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    Msg.get(
                            "error.session.workspaceInvalid",
                            workspaceRoots,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    e);
        }
        if (currentWorkspaceRootIndex < 0 || currentWorkspaceRootIndex >= declaredRoots.size()) {
            throw new IllegalArgumentException(
                    "currentWorkspaceRootIndex must be between 0 and "
                            + (declaredRoots.size() - 1));
        }
        for (Path admittedRoot : declaredRoots) {
            try {
                java.nio.file.Files.createDirectories(admittedRoot);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        Msg.get(
                                "error.session.workspaceInvalid",
                                admittedRoot,
                                e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()),
                        e);
            }
        }
        String admittedWorkspaceRoots =
                String.join(",", declaredRoots.stream().map(Path::toString).toList());

        String resolvedName;
        if (sessionName == null || sessionName.isEmpty()) {
            // Implicit session name: always produce a workspace-unique name so `/session create ds`
            // succeeds even when another `ds` session exists in this workspace. The generated name
            // keeps the pattern name as a human-readable prefix.
            resolvedName = generateUniqueSessionName(owner, patternName, admittedWorkspaceRoots);
        } else {
            resolvedName = sessionName;
            // Uniqueness is scoped to (owner, name, workspaceRoots): the same name is allowed in a
            // different workspace. The match is an exact CSV-string compare; a legacy row with
            // workspace_roots = NULL is a distinct workspace from any new row (SQL NULL != 'X'), so
            // it does not block creation in a concrete workspace.
            if (sessions.findByOwnerAndNameAndWorkspaceRoots(
                            owner, resolvedName, admittedWorkspaceRoots)
                    .isPresent()) {
                throw new IllegalArgumentException(
                        Msg.get("error.session.nameExists", resolvedName, admittedWorkspaceRoots));
            }
        }

        SessionEntity session =
                sessions.save(
                        new SessionEntity(
                                owner,
                                resolvedName,
                                admittedWorkspaceRoots,
                                currentWorkspaceRootIndex,
                                toolResultPresentation));
        ModelBinding cache = tierRegistry.resolve(owner, pattern.getTier());
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        pattern.getId(),
                        AgentEntity.Role.PRIMARY,
                        resolvedName,
                        pattern.getTier(),
                        cache);
        agent = agents.save(agent);
        session.setPrimaryAgentId(agent.getId());
        return sessions.save(session);
    }

    /**
     * Generates a session name of the form {@code baseName-xxxxxxxx} that is not already used by
     * {@code owner} in {@code workspaceRoots}. The suffix is 8 lowercase hex digits from a
     * ThreadLocalRandom source; the lookup loop protects against an astronomically unlikely
     * collision.
     */
    private @NonNull String generateUniqueSessionName(
            @NonNull String owner, @NonNull String baseName, @NonNull String workspaceRoots) {
        for (int attempt = 0; attempt < 16; attempt++) {
            String suffix = String.format("%08x", ThreadLocalRandom.current().nextInt());
            String candidate = baseName + "-" + suffix;
            if (sessions.findByOwnerAndNameAndWorkspaceRoots(owner, candidate, workspaceRoots)
                    .isEmpty()) {
                return candidate;
            }
        }
        // Fall back to a full timestamped suffix if random space is exhausted.
        return baseName + "-" + System.currentTimeMillis();
    }

    /**
     * Returns every session owned by {@code owner}, irrespective of workspace binding. Used by the
     * REST facade ({@link top.focess.veto.controller.SessionController#list}) where there is no
     * terminal cwd to scope to — the web UI is expected to group / filter as it wishes.
     */
    public @NonNull List<SessionEntity> listSessions(@NonNull String owner) {
        return sessions.findByOwner(owner);
    }

    /**
     * Returns the owner's sessions whose {@link SessionEntity#getWorkspaceRoots()} contains the
     * terminal's {@code cwd} (at-or-under one of the roots). {@code cwd} is the terminal's current
     * working directory — the session's bound workspace is fixed at creation, so a terminal outside
     * that scope sees an empty list. The strict binding is what makes sessions "belong" to a
     * workspace; different workspaces show different sessions.
     *
     * <p>{@code workspaceRoots} from the session is treated as CSV when present; null/blank is
     * treated as "matches any workspace" for backward compatibility with legacy rows written before
     * the field was populated.
     *
     * @param owner the session owner
     * @param cwd the terminal's current working directory; never {@code null} (the terminal always
     *     reports its JVM working dir in the IPC {@code Hello} handshake)
     */
    public @NonNull List<SessionEntity> listSessions(@NonNull String owner, @NonNull String cwd) {
        return sessions.findByOwner(owner).stream()
                .filter(s -> isInWorkspace(s.getWorkspaceRoots(), cwd))
                .toList();
    }

    /**
     * Attaches the terminal to an existing session. Get-or-creates the primary agent (seeding
     * replayed history) and returns its LLM config. Sets the terminal's active session on success.
     *
     * <p>Strict workspace binding: the session's {@code workspaceRoots} (its creation-time cwd)
     * must contain the terminal's current {@code cwd} — the terminal is expected to be at-or-under
     * one of the session's roots. A terminal in a different workspace cannot activate the session;
     * it gets a clear error pointing at the session's binding. Different workspaces show different
     * sessions in {@link #listSessions}; the auto-resume path uses the same scope.
     */
    @Transactional
    public @NonNull Optional<LlmConfig> activate(
            @NonNull String terminalId,
            @NonNull String sessionName,
            @NonNull String owner,
            @NonNull String cwd) {
        // Find by owner + name (the same name may exist in multiple workspaces) and pick the one
        // whose workspace contains the terminal's cwd. With the create-time uniqueness check on
        // (owner, name, workspaceRoots), at most one session per name is bound to a given cwd —
        // except legacy rows with workspace_roots = NULL, which match every cwd. When both a
        // legacy row and a concrete-workspace row match, the concrete one wins (more specific).
        List<SessionEntity> candidates =
                sessions.findByOwner(owner).stream()
                        .filter(s -> sessionName.equals(s.getName()))
                        .filter(s -> isInWorkspace(s.getWorkspaceRoots(), cwd))
                        .sorted(SessionService::compareActivationCandidates)
                        .toList();
        if (candidates.isEmpty()) {
            // Disambiguate the error: is it "not found at all" or "found but in a different
            // workspace"? The user benefits from knowing they can cd to activate. Stream over the
            // owner's sessions - findByNameAndOwner would throw on cross-workspace duplicates.
            sessions.findByOwner(owner).stream()
                    .filter(s -> sessionName.equals(s.getName()))
                    .findFirst()
                    .ifPresent(
                            s -> {
                                throw new IllegalArgumentException(
                                        workspaceMismatchMessage(
                                                sessionName, s.getWorkspaceRoots(), cwd));
                            });
            throw new IllegalArgumentException(Msg.get("error.session.notFound", sessionName));
        }
        SessionEntity session = candidates.get(0);
        AgentEntity agent = primaryAgent(session);
        if (agent == null) {
            return Optional.empty();
        }

        ModelBinding resolved = tierRegistry.resolve(owner, agent.getTier());
        AgentRunner.LlmBinding binding = standaloneBinding(resolved);
        List<TurnRecord> history = historyLoader.load(session.getId(), agent.getId());
        agentService.getOrCreateAgent(
                session.getId(),
                agent.getId(),
                binding,
                history,
                agentService.userIdForOwner(owner),
                owner,
                session.getWorkspaceRoots(),
                session.getCurrentWorkspaceRootIndex(),
                session.getToolResultPresentation());
        session.touch();
        sessions.save(session);
        activeSessions.put(terminalId, session.getId());

        return Optional.of(llmConfig(resolved));
    }

    /**
     * Auto-resumes the owner's most-recently-active session in the terminal's current {@code cwd}
     * after a server restart or terminal reconnect (the in-memory active-session map is wiped on
     * restart). Replays durable history by delegating to {@link #activate}. Returns empty if the
     * owner has no in-workspace session, or the most-recent in-workspace session has no primary
     * agent — out-of-workspace sessions are intentionally skipped so a terminal never silently
     * resumes into a session that operates on a different directory tree.
     */
    @Transactional
    public @NonNull Optional<LlmConfig> resumeLastSession(
            @NonNull String terminalId, @NonNull String owner, @NonNull String cwd) {
        return sessions.findByOwner(owner).stream()
                .filter(s -> isInWorkspace(s.getWorkspaceRoots(), cwd))
                .max(SessionService::compareLastActiveAscending)
                .flatMap(session -> activate(terminalId, session.getName(), owner, cwd));
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

    /**
     * Deletes a session and cascades: detaches any terminal attached to it, terminates the
     * in-memory agent, and removes the session's agent instances + the session row. Mirrors the
     * per-session slice of {@link top.focess.veto.security.UserAdminService#deleteUser}.
     *
     * <p>Because two sessions may share a name across workspaces, the {@code (owner, name)} pair is
     * not unique; the REST caller ({@code SessionController}) has no workspace context to
     * disambiguate with, so every matching session is deleted. Returns {@code false} when no
     * session matches.
     */
    @Transactional
    public boolean delete(@NonNull String owner, @NonNull String sessionName) {
        List<SessionEntity> matches =
                sessions.findByOwner(owner).stream()
                        .filter(s -> sessionName.equals(s.getName()))
                        .toList();
        if (matches.isEmpty()) {
            return false;
        }
        for (SessionEntity session : matches) {
            String sessionId = session.getId();
            activeSessions.entrySet().removeIf(e -> sessionId.equals(e.getValue()));
            agentService.remove(sessionId);
            agents.deleteBySessionId(sessionId);
            sessions.delete(session);
        }
        return true;
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
        return Optional.of(llmConfig(tierRegistry.resolve(session.getOwner(), agent.getTier())));
    }

    /**
     * Resolves the LLM config for a session by name + owner (REST path). The REST controller has no
     * terminalId (that's an IPC concept); it resolves the session by name for the authenticated
     * user and returns the config + session id in one call.
     */
    public @NonNull Optional<SessionConfig> resolveByName(
            @NonNull String name, @NonNull String owner) {
        // Duplicate-tolerant: several sessions may share a name across workspaces, and the REST
        // caller has no workspace to disambiguate with - the most-recently-active one wins.
        SessionEntity session =
                sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(name, owner).orElse(null);
        if (session == null) return Optional.empty();
        AgentEntity agent = primaryAgent(session);
        if (agent == null) return Optional.empty();
        LlmConfig config = llmConfig(tierRegistry.resolve(session.getOwner(), agent.getTier()));
        return Optional.of(
                new SessionConfig(session.getId(), config, session.getToolResultPresentation()));
    }

    /** A resolved session's id + LLM config, for the REST prompt path. */
    public record SessionConfig(
            @NonNull String sessionId,
            @NonNull LlmConfig config,
            @NonNull ToolResultPresentationMode toolResultPresentation) {}

    /**
     * Activates a session for the REST prompt path (no terminal, no cwd scoping): resolves the
     * session duplicate-tolerantly (most-recently-active wins, same as {@link #resolveByName}),
     * get-or-creates its primary agent with the DB primary agent id + the session's workspace +
     * replayed history - so HITL vetoes park under the id {@link #primaryAgentIdFor} reports and
     * tools run against the session's roots - touches lastActiveAt, and returns the session id +
     * LLM config. Empty when the session or its primary agent does not exist.
     */
    @Transactional
    public @NonNull Optional<SessionConfig> activateForRest(
            @NonNull String name, @NonNull String owner) {
        SessionEntity session =
                sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(name, owner).orElse(null);
        if (session == null) return Optional.empty();
        AgentEntity agent = primaryAgent(session);
        if (agent == null) return Optional.empty();
        ModelBinding resolved = tierRegistry.resolve(session.getOwner(), agent.getTier());
        List<TurnRecord> history = historyLoader.load(session.getId(), agent.getId());
        agentService.getOrCreateAgent(
                session.getId(),
                agent.getId(),
                standaloneBinding(resolved),
                history,
                agentService.userIdForOwner(owner),
                owner,
                session.getWorkspaceRoots(),
                session.getCurrentWorkspaceRootIndex(),
                session.getToolResultPresentation());
        session.touch();
        sessions.save(session);
        return Optional.of(
                new SessionConfig(
                        session.getId(), llmConfig(resolved), session.getToolResultPresentation()));
    }

    /**
     * The session's primary agent id - the key the HITL registry parks vetoes under. Resolved
     * duplicate-tolerantly (most-recently-active wins), same as {@link #resolveByName}. Empty when
     * the session has no primary agent.
     */
    public @NonNull Optional<String> primaryAgentIdFor(
            @NonNull String name, @NonNull String owner) {
        SessionEntity session =
                sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(name, owner).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        AgentEntity agent = primaryAgent(session);
        return agent != null ? Optional.of(agent.getId()) : Optional.empty();
    }

    /**
     * Builds the standalone agent's binding from a resolved tier binding (no prompt-base override).
     */
    private AgentRunner.@NonNull LlmBinding standaloneBinding(@NonNull ModelBinding resolved) {
        return new AgentRunner.LlmBinding(
                resolved.provider(),
                resolved.model(),
                resolved.credentialKey(),
                new LlmOptions(
                        resolved.temperature(),
                        null,
                        resolved.maxOutputTokens(),
                        LlmOptions.defaults().timeout()),
                null,
                resolved.baseUrl());
    }

    private @NonNull LlmConfig llmConfig(@NonNull ModelBinding resolved) {
        return new LlmConfig(
                resolved.provider(),
                resolved.model(),
                resolved.credentialKey(),
                resolved.baseUrl());
    }

    public @NonNull Optional<Agent> activeAgent(@NonNull String terminalId) {
        String sessionId = activeSessions.get(terminalId);
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(agentService.agentsView().get(sessionId));
    }

    private AgentEntity primaryAgent(@NonNull SessionEntity session) {
        String primaryAgentId = session.getPrimaryAgentId();
        if (primaryAgentId == null) return null;
        return agents.findById(primaryAgentId).orElse(null);
    }

    private static int compareActivationCandidates(
            @NonNull SessionEntity left, @NonNull SessionEntity right) {
        boolean leftLegacy = left.getWorkspaceRoots() == null;
        boolean rightLegacy = right.getWorkspaceRoots() == null;
        int specificity = Boolean.compare(leftLegacy, rightLegacy);
        return specificity != 0 ? specificity : -compareLastActiveAscending(left, right);
    }

    private static int compareLastActiveAscending(
            @NonNull SessionEntity left, @NonNull SessionEntity right) {
        Instant leftTime = left.getLastActiveAt();
        Instant rightTime = right.getLastActiveAt();
        if (leftTime == null) return rightTime == null ? 0 : -1;
        if (rightTime == null) return 1;
        return leftTime.compareTo(rightTime);
    }

    /**
     * Builds the user-facing error when a session exists for {@code (owner, name)} but its
     * workspace does not contain the terminal's current {@code cwd}. Pulled out so {@link
     * #activate} (which now matches by owner+name+workspace) produces a single canonical message
     * whether the wrong-workspace session was filtered out or surfaced via the fallback lookup.
     */
    private static @NonNull String workspaceMismatchMessage(
            @NonNull String sessionName, String sessionWorkspaceRoots, @NonNull String cwd) {
        return Msg.get(
                "error.session.workspaceMismatch",
                sessionName,
                sessionWorkspaceRoots == null ? "" : sessionWorkspaceRoots,
                cwd);
    }

    /**
     * Returns true when {@code cwd} is at or under one of the roots in {@code
     * sessionWorkspaceRoots} (CSV of host paths). Null/blank {@code sessionWorkspaceRoots} is
     * treated as "matches any workspace" for backward compatibility with legacy rows written before
     * the field was populated. Both paths are normalized via {@code toAbsolutePath().normalize()}
     * so a trailing slash, {@code .} / {@code ..} segments, or relative-vs-absolute spelling never
     * causes a false negative.
     *
     * <p>This is a purely lexical check — symlink-resolved canonicalization is the job of {@link
     * top.focess.veto.agent.workspace.PathResolver} at tool-call time, not session routing. A
     * terminal that has symlinked itself into a session's workspace is still "in" it for
     * session-routing purposes; the agent's tool calls will then resolve symlinks per their own
     * canonicalization rules.
     */
    private static boolean isInWorkspace(String sessionWorkspaceRoots, @NonNull String cwd) {
        if (sessionWorkspaceRoots == null || sessionWorkspaceRoots.isBlank()) {
            return true;
        }
        Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
        return Arrays.stream(sessionWorkspaceRoots.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(
                        root -> {
                            Path rootPath = Path.of(root).toAbsolutePath().normalize();
                            return cwdPath.equals(rootPath) || cwdPath.startsWith(rootPath);
                        });
    }
}
