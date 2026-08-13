package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ScreeningMode;
import top.focess.veto.agent.screening.SlmRelevanceProvider;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.UniformLLMCaller;

/**
 * The shared agent service ("Multi-Client Unification"). Both the ZMQ terminal ({@code
 * PromptHandler}) and the REST controllers are thin facades that delegate here — loop execution is
 * hosted exclusively in {@code veto-core}, asynchronously on virtual threads via {@link
 * AgentRunner} managed per {@link VetoAgent}.
 *
 * <p>Resolves (or creates) an agent per transport identity, binds its model configuration, submits
 * a prompt, and blocks for the result. Owns agent lifecycle + veto resolution.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final Duration DEFAULT_AWAIT = Duration.ofMinutes(5);

    private final ToolEngine mcpEngine;
    private final HitlRegistry hitlRegistry;
    private final IngressDefense ingressDefense;
    private final PromptCompiler promptCompiler;
    private final UniformLLMCaller caller;
    private final ObjectMapper objectMapper;
    private final List<LoopInterceptor> interceptors;
    private final Workspace defaultWorkspace;
    private final String pathMode;
    private final RoleToolFilter roleToolFilter;
    private final long maxCallsPerEpisode;
    private final DeployerPolicy deployerPolicy;
    // The Part-8 Delta-broker — optional (nullable in tests); when present, threaded into each
    // AgentRunner so loop emissions publish per-session DeltaFrames for transports to stream.
    private final @Nullable DeltaBroker deltaBroker;
    // The raw-turn write-through log — optional (nullable in tests); when present, threaded into
    // each AgentRunner so appendTurn persists to the raw-turn audit/replay log.
    private final top.focess.veto.memory.@Nullable TurnLogService turnLogService;
    private final top.focess.veto.sandbox.@NonNull BackgroundTaskManager backgroundTaskManager;

    /**
     * The fallback memory-tenant userId for legacy/test paths that bypass session activation (the
     * {@code submit} overloads with no owner). The activate path derives a real per-user tenant via
     * {@link #userIdForOwner(String)} instead, so memories and turn logs attribute to the actual
     * session owner.
     */
    static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ConcurrentHashMap<String, VetoAgent> agents = new ConcurrentHashMap<>();

    public AgentService(
            @NonNull ToolEngine mcpEngine,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull IngressDefense ingressDefense,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper objectMapper,
            @Nullable List<LoopInterceptor> interceptors,
            @NonNull RoleToolFilter roleToolFilter,
            @Value("${veto.workspace.path-mode:REAL}") @NonNull String pathMode,
            @Value("${veto.breaker.max_calls_per_episode:50}") long maxCallsPerEpisode,
            @Value("${veto.security.deployer-policy:FULL_ACCESS}")
                    @NonNull String deployerPolicyRaw,
            @Value("${veto.security.screening-mode:STRICT}") @NonNull String screeningModeRaw,
            @Nullable DeltaBroker deltaBroker,
            top.focess.veto.memory.@Nullable TurnLogService turnLogService,
            top.focess.veto.sandbox.@NonNull BackgroundTaskManager backgroundTaskManager) {
        this.mcpEngine = mcpEngine;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.pathMode = pathMode;
        this.defaultWorkspace = Workspace.fromConfig("", "", pathMode);
        this.roleToolFilter = roleToolFilter;
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        this.deployerPolicy = parseDeployerPolicy(deployerPolicyRaw);
        if (this.deployerPolicy == DeployerPolicy.FULL_ACCESS) {
            log.warn(
                    "deployer-policy=FULL_ACCESS: the agent can read application.yml and any secrets"
                            + " stored in it. Do not store high-value secrets in application.yml under"
                            + " this policy; use env vars or the keystead vault.");
        }
        // Thread the runtime screening matrix + a fallback workspace to the (shared) HITL registry.
        // Each session registers its own workspace per-agentId at create time (see createAgent /
        // createMate); the default here covers legacy call paths that bypass session creation.
        this.hitlRegistry.setScreeningMode(parseScreeningMode(screeningModeRaw));
        this.hitlRegistry.setDefaultWorkspace(this.defaultWorkspace);
        this.deltaBroker = deltaBroker;
        this.turnLogService = turnLogService;
        this.backgroundTaskManager = backgroundTaskManager;
    }

    /**
     * Resolves (or creates) the agent for the transport id, binds the model configuration, submits
     * the prompt, and blocks for the result. Returns the {@link AgentResult}.
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding) {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        agent.setLocale(LocaleContextHolder.getLocale());
        agent.submit(prompt);
        try {
            return agent.await(DEFAULT_AWAIT);
        } catch (TimeoutException e) {
            log.warn("Agent {} await timed out", agentKey);
            return AgentResult.failure(Msg.get(agent.locale(), "error.agent.timedOut"), Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResult.failure(
                    Msg.get(agent.locale(), "error.agent.interrupted"), Map.of());
        }
    }

    /**
     * Fire-and-forget submit: resolves (or creates) the agent, binds the model configuration, and
     * starts the episode, returning as soon as the run is enqueued. The episode's progress and
     * outcome travel as {@link top.focess.veto.bus.DeltaFrame} events on the {@code DeltaBroker}
     * (session-scoped), and the durable result lands in the turn log — callers subscribe and read,
     * they do not block here. This is the transport shape the web UI uses: REST submits, WebSocket
     * streams, REST GET history stays the authoritative read.
     */
    public void submitNow(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding) {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        agent.setLocale(LocaleContextHolder.getLocale());
        agent.submit(prompt);
    }

    /** Synchronous submit with a live result message (for the terminal path). */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        agent.setLocale(LocaleContextHolder.getLocale());
        agent.submit(prompt);
        return agent.await(timeout);
    }

    /**
     * Synchronous submit that streams each user-facing {@code response.message} the agent emits to
     * {@code messageSink} while the loop runs (the emission seam), then returns the final result.
     * The sink is attached before the prompt is enqueued (no missed messages) and detached in the
     * finally (no leak across episodes / stale senders). A JVM EventBus + ZmqServer Delta-frame
     * broker will eventually sit between the agent and the wire; this is the direct in-process
     * handoff for the terminal path until then.
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout,
            @Nullable Consumer<String> messageSink)
            throws TimeoutException, InterruptedException {
        return submit(agentKey, prompt, binding, timeout, messageSink, null);
    }

    /**
     * Synchronous submit that streams user-facing messages to {@code messageSink} and forwards HITL
     * veto prompts to {@code vetoSink} while the loop runs, then returns the final result. Both
     * sinks are attached before the prompt is enqueued (no missed events) and detached in the
     * finally (no leak across episodes / stale senders). The veto sink renders a picker; the user's
     * reply resolves the parked veto via {@link #resolveVeto}.
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout,
            @Nullable Consumer<String> messageSink,
            @Nullable Consumer<VetoPrompt> vetoSink)
            throws TimeoutException, InterruptedException {
        return submit(agentKey, prompt, binding, timeout, messageSink, vetoSink, null);
    }

    /**
     * Synchronous submit that additionally streams the agent's interim thoughts to {@code
     * thoughtSink} (rendered distinct from user-facing messages). The thought sink is attached and
     * detached alongside the message and veto sinks so a transport sees the full thought-then-
     * message sequence for every turn with no leak across episodes.
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout,
            @Nullable Consumer<String> messageSink,
            @Nullable Consumer<VetoPrompt> vetoSink,
            @Nullable Consumer<String> thoughtSink)
            throws TimeoutException, InterruptedException {
        return submit(
                agentKey, prompt, binding, timeout, messageSink, vetoSink, thoughtSink, null, null);
    }

    /**
     * Synchronous submit that additionally streams the agent's per-tool-call indicators (via {@code
     * toolCallSink}) and the framed observations the model received (via {@code toolResultSink}) so
     * the terminal can render a Claude-Code-style transparency trace. Both sinks are attached
     * before the prompt is enqueued (no missed events) and detached in the finally (no leak across
     * episodes). The tool result body is the exact text the model sees, so the user can verify the
     * call it belongs to by the self-describing "Observation (tool(args)) [...]" header
     * IngressDefense writes.
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout,
            @Nullable Consumer<String> messageSink,
            @Nullable Consumer<VetoPrompt> vetoSink,
            @Nullable Consumer<String> thoughtSink,
            @Nullable Consumer<AgentRunner.ToolCallEvent> toolCallSink,
            @Nullable Consumer<AgentRunner.ToolResultEvent> toolResultSink)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        agent.setLocale(LocaleContextHolder.getLocale());
        if (messageSink != null) {
            agent.addMessageListener(messageSink);
        }
        if (vetoSink != null) {
            agent.addVetoListener(vetoSink);
        }
        if (thoughtSink != null) {
            agent.addThoughtListener(thoughtSink);
        }
        if (toolCallSink != null) {
            agent.addToolCallListener(toolCallSink);
        }
        if (toolResultSink != null) {
            agent.addToolResultListener(toolResultSink);
        }
        try {
            agent.submit(prompt);
            return agent.await(timeout);
        } finally {
            if (messageSink != null) {
                agent.removeMessageListener(messageSink);
            }
            if (vetoSink != null) {
                agent.removeVetoListener(vetoSink);
            }
            if (thoughtSink != null) {
                agent.removeThoughtListener(thoughtSink);
            }
            if (toolCallSink != null) {
                agent.removeToolCallListener(toolCallSink);
            }
            if (toolResultSink != null) {
                agent.removeToolResultListener(toolResultSink);
            }
        }
    }

    /**
     * Synchronous submit with explicit user identity. The {@code userId} is threaded through to
     * {@link AgentRunner} for memory capture and group ownership, enabling multi-user tenant
     * isolation. Use this overload when the transport has authenticated the user.
     *
     * @param agentKey the transport identity (unique per session)
     * @param prompt the user's prompt
     * @param binding the LLM configuration
     * @param timeout the maximum time to wait
     * @param userId the authenticated user's id (for memory/group isolation)
     * @return the agent result
     */
    public AgentResult submit(
            @NonNull String agentKey,
            @NonNull String prompt,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Duration timeout,
            @NonNull UUID userId)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding, userId));
        agent.bind(binding);
        agent.setLocale(LocaleContextHolder.getLocale());
        agent.submit(prompt);
        return agent.await(timeout);
    }

    /**
     * Gets or creates the agent for a session id, seeding replayed history on first creation. The
     * session id is the agent key (replacing terminal-id keying) so an agent is shared across any
     * interface attached to the session. Seeding runs before the first {@code submit} (the loop
     * parks on its action queue while IDLE), and is idempotent via {@link AgentRunner#seedHistory}.
     */
    public Agent getOrCreateAgent(
            @NonNull String sessionId,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull List<TurnRecord> history,
            @NonNull UUID userId) {
        return getOrCreateAgent(sessionId, binding, history, userId, null);
    }

    /**
     * Gets or creates the agent for a session id with the session's workspace, seeding replayed
     * history on first creation. The workspace is resolved from {@code workspaceRoots} (CSV of host
     * paths; null/blank falls back to the JVM working dir) so each session's grants + path
     * resolution scope to its own workspace rather than a process-global root.
     *
     * <p>The session id is the agent key (replacing terminal-id keying) so an agent is shared
     * across any interface attached to the session. Seeding runs before the first {@code submit}
     * (the loop parks on its action queue while IDLE), and is idempotent via {@link
     * AgentRunner#seedHistory}. The workspace is fixed at first creation; later calls with a
     * different {@code workspaceRoots} reuse the existing agent (and its original workspace).
     */
    public Agent getOrCreateAgent(
            @NonNull String sessionId,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull List<TurnRecord> history,
            @NonNull UUID userId,
            @Nullable String workspaceRoots) {
        return getOrCreateAgent(sessionId, null, binding, history, userId, null, workspaceRoots);
    }

    /**
     * Gets or creates the agent for a session id, threading the DB primary agent id so persisted
     * turns land under the right agent stream. {@code primaryAgentId} is the {@code AgentEntity} id
     * (a UUID); when present it becomes the persona id (and thus turn_records.agent_id) and the
     * runner's session id is stamped to {@code sessionId} (session.getId()). When null the legacy
     * behavior (mint a fresh persona UUID, session id = persona id) is preserved.
     *
     * @param owner the session owner (username) whose model-tier profile resolves the agent's tier;
     *     threaded onto the runner's {@link top.focess.veto.agent.mcp.ToolCallContext} so group
     *     spawns resolve per-user. Null in legacy/test paths.
     */
    public Agent getOrCreateAgent(
            @NonNull String sessionId,
            @Nullable String primaryAgentId,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull List<TurnRecord> history,
            @NonNull UUID userId,
            @Nullable String owner,
            @Nullable String workspaceRoots) {
        boolean[] created = {false};
        Workspace workspace = buildWorkspace(workspaceRoots);
        VetoAgent agent =
                agents.computeIfAbsent(
                        sessionId,
                        k -> {
                            created[0] = true;
                            return createAgent(
                                    k, primaryAgentId, binding, userId, owner, workspace);
                        });
        agent.bind(binding);
        if (created[0] && history != null && !history.isEmpty()) {
            agent.seedHistory(history);
        }
        return agent;
    }

    /**
     * Resolves a pending veto from a user-chosen option name (the {@code Input} reply). Validates
     * the option against the offered set (stashed at register time) and builds the resolution
     * (masking per the option). Returns {@code false} if no pending veto exists for the key.
     */
    public boolean resolveVeto(
            @NonNull String agentId, @NonNull String callId, @NonNull String optionName) {
        return hitlRegistry.resolveOption(agentId, callId, optionName);
    }

    /**
     * Declines a pending veto (cancel-during-veto): resolves with the scenario's refusal so the
     * agent refuses this call and continues. Returns {@code false} if no pending veto exists.
     */
    public boolean declineVeto(@NonNull String agentId, @NonNull String callId) {
        return hitlRegistry.declineOption(agentId, callId);
    }

    /**
     * Declines every pending veto for the agent (a transport's cancel while parked): each resolves
     * with the refusal option so the agent unstucks fail-safe. Returns the number declined.
     */
    public int declineAllVetoes(@NonNull String agentId) {
        return hitlRegistry.declineAll(agentId);
    }

    /** The live agent for a transport id (for history / state inspection). */
    public @Nullable VetoAgent agent(@NonNull String agentKey) {
        return agents.get(agentKey);
    }

    /**
     * A live unmodifiable view of all managed agents (for the terminal facade's session inspection
     * — turn counts for the status bar / {@code /status}). Callers must not mutate.
     */
    public Map<String, VetoAgent> agentsView() {
        return Collections.unmodifiableMap(agents);
    }

    /** Removes an agent (logout / disconnect). */
    public void remove(@NonNull String agentKey) {
        VetoAgent a = agents.remove(agentKey);
        if (a != null) {
            a.terminate();
            // Kill any background tasks the agent launched so they don't outlive their owner.
            backgroundTaskManager.stopAll(a.id());
        }
    }

    private @NonNull VetoAgent createAgent(
            @NonNull String agentKey, AgentRunner.@NonNull LlmBinding binding) {
        return createAgent(agentKey, binding, DEFAULT_USER_ID, defaultWorkspace);
    }

    private VetoAgent createAgent(
            @NonNull String agentKey,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId) {
        return createAgent(agentKey, binding, userId, defaultWorkspace);
    }

    private VetoAgent createAgent(
            @NonNull String agentKey,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId,
            @NonNull Workspace workspace) {
        return createAgent(agentKey, null, binding, userId, null, workspace);
    }

    // The DB-backed create path: agentKey is session.getId() (a UUID) and primaryAgentId is the
    // AgentEntity id (also a UUID). persona.id() becomes primaryAgentId so turn_records.agent_id
    // names this agent's stream, while the runner's session id is stamped to session.getId() so the
    // session_id column groups the session's 1+N agent streams. When primaryAgentId is null (legacy
    // /
    // test path) the runner keeps its constructor default (persona.id()) as the session id.
    private VetoAgent createAgent(
            @NonNull String agentKey,
            @Nullable String primaryAgentId,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId,
            @Nullable String owner,
            @NonNull Workspace workspace) {
        AgentPersona persona = buildPersona(agentKey, primaryAgentId, binding);
        // Register this agent's workspace on the HITL registry under its persona id so grant
        // matching + path canonicalization scope to this session's workspace.
        hitlRegistry.setWorkspace(persona.id(), workspace);
        ReadHistory readHistory = new ReadHistory();
        ProtectedSet userProtectedSet = protectedSetFor(agentKey, workspace);
        Gateway gateway =
                new Gateway(
                        workspace,
                        new DangerComputation(),
                        SlmRelevanceProvider.degraded(),
                        deployerPolicy,
                        userProtectedSet,
                        readHistory);
        AgentRunner runner =
                new AgentRunner(
                        persona.id(),
                        persona,
                        mcpEngine,
                        gateway,
                        hitlRegistry,
                        ingressDefense,
                        interceptors,
                        promptCompiler,
                        caller,
                        objectMapper,
                        maxCallsPerEpisode,
                        binding,
                        deltaBroker,
                        userId,
                        turnLogService,
                        backgroundTaskManager);
        // Stamp the session owner so group-spawned Mates / Leaders resolve their tier against the
        // user's active model-tier profile via the ToolCallContext.
        runner.setOwner(owner);
        if (primaryAgentId != null) {
            runner.setSessionId(UUID.fromString(agentKey));
        }
        return new VetoAgent(persona, runner);
    }

    /**
     * Builds a Mate {@link Agent} (a Leader-delegated worker) for the group engine. Unlike {@link
     * #createAgent} this does not register the agent under a transport key (Mates are not
     * transport-addressable — {@code GroupSpawner} tracks their lifecycle) and uses an empty {@link
     * ProtectedSet} (Mates inherit screening via the Gateway; per-user isolation is not wired for
     * spawned Mates yet).
     */
    public Agent createMate(AgentPersona persona, AgentRunner.LlmBinding binding) {
        return createMate(persona, binding, DEFAULT_USER_ID, null, defaultWorkspace);
    }

    /**
     * Builds a Mate {@link Agent} bound to the given workspace, inheriting the default user id.
     * Used by the group engine to spawn a Mate / one-shot Leader in the calling session's
     * workspace.
     */
    public Agent createMate(
            @NonNull AgentPersona persona,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull Workspace workspace) {
        return createMate(persona, binding, DEFAULT_USER_ID, null, workspace);
    }

    /**
     * Builds a Mate {@link Agent} with explicit user identity for multi-user tenant isolation. The
     * Mate inherits the Leader's userId so its memory capture is scoped to the same tenant.
     */
    public Agent createMate(
            @NonNull AgentPersona persona,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId) {
        return createMate(persona, binding, userId, null, defaultWorkspace);
    }

    /**
     * Builds a Mate {@link Agent} bound to the given workspace (the calling session's). The Mate
     * inherits the session's workspace so its tool calls resolve paths + match grants against the
     * same roots as the delegating agent.
     */
    public Agent createMate(
            @NonNull AgentPersona persona,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId,
            @NonNull Workspace workspace) {
        return createMate(persona, binding, userId, null, workspace);
    }

    /**
     * Builds a Mate {@link Agent} with explicit user identity <em>and</em> session owner. The Mate
     * inherits the Leader's userId (memory tenant) and owner (whose model-tier profile resolves the
     * Mate's tier). This is the overload the production group factory ({@link
     * top.focess.veto.group.GroupAgentFactory}) uses - it reads both from the calling agent's
     * {@link top.focess.veto.agent.mcp.ToolCallContext} so the Mate resolves its model against the
     * same user's active profile as the Leader.
     */
    public Agent createMate(
            @NonNull AgentPersona persona,
            AgentRunner.@NonNull LlmBinding binding,
            @NonNull UUID userId,
            @Nullable String owner,
            @NonNull Workspace workspace) {
        // Re-scope the persona's tools to its role. The persona may have been built with the full
        // standalone manifest before its role (MATE/LEADER) was known; the RoleToolFilter narrows
        // it to the role's allow-list (MATE: no group tools; LEADER: read + arrange only).
        AgentPersona scoped = persona.withWhitelistedTools(roleToolFilter.resolve(persona.role()));
        hitlRegistry.setWorkspace(scoped.id(), workspace);
        ReadHistory readHistory = new ReadHistory();
        // Mates are not wired with per-user deployer defaults (see createMate javadoc), but the
        // app's own config/audit material is system-wide and must be shielded under
        // non-FULL_ACCESS.
        ProtectedSet mateProtectedSet =
                this.deployerPolicy == DeployerPolicy.FULL_ACCESS
                        ? ProtectedSet.empty()
                        : ProtectedSet.empty().withSystemProtected(systemProtectedPaths());
        Gateway gateway =
                new Gateway(
                        workspace,
                        new DangerComputation(),
                        SlmRelevanceProvider.degraded(),
                        deployerPolicy,
                        mateProtectedSet,
                        readHistory);
        AgentRunner runner =
                new AgentRunner(
                        scoped.id(),
                        scoped,
                        mcpEngine,
                        gateway,
                        hitlRegistry,
                        ingressDefense,
                        interceptors,
                        promptCompiler,
                        caller,
                        objectMapper,
                        maxCallsPerEpisode,
                        binding,
                        deltaBroker,
                        userId,
                        turnLogService,
                        backgroundTaskManager);
        // Stamp the session owner so the Mate (or one-shot Leader) resolves its tier against the
        // user's active model-tier profile via the ToolCallContext.
        runner.setOwner(owner);
        return new VetoAgent(scoped, runner);
    }

    /**
     * Builds the agent persona. Resolves the tool set from the {@link ToolEngine}'s active native +
     * remote + agent tools (agent tools like {@code load_skill} and {@code think} are always-on
     * control/meta tools, included in every agent's manifest). Full {@code ~/.veto/} persona
     * resolution (skills, per-agent tool grants) is not yet wired — the default grants all
     * registered tools.
     */
    private @NonNull AgentPersona buildPersona(
            @NonNull String agentKey, AgentRunner.@NonNull LlmBinding binding) {
        return buildPersona(agentKey, null, binding);
    }

    // persona.id() is the agent identity written to turn_records.agent_id and used as the HITL
    // workspace key. When the DB path supplies primaryAgentId (the AgentEntity id, a UUID) we adopt
    // it
    // verbatim so persisted turns group under the right agent stream and resume can find them;
    // absent
    // that (legacy/test path) we mint a fresh UUID just as before.
    private AgentPersona buildPersona(
            String agentKey, @Nullable String primaryAgentId, AgentRunner.LlmBinding binding) {
        Set<ToolDefinition> tools = roleToolFilter.resolve(Role.STANDALONE);
        String personaId = primaryAgentId != null ? primaryAgentId : UUID.randomUUID().toString();
        return new AgentPersona(
                personaId,
                SystemPromptResolver.NAME,
                SystemPromptResolver.DESCRIPTION,
                tools,
                List.of());
    }

    /**
     * The fallback workspace agents resolve paths against when no session workspace is set (for
     * tests).
     */
    public Workspace workspace() {
        return defaultWorkspace;
    }

    /**
     * Derives the stable memory-tenant userId for a session owner. Users are keyed by username (no
     * UUID column on {@code UserEntity}), so a name-based UUID ({@link UUID#nameUUIDFromBytes})
     * gives each owner a distinct, deterministic tenant id. Memories and turn logs then attribute
     * to the real user across sessions and restarts, replacing the {@code DEFAULT_USER_ID}
     * placeholder on the activate path. Used by the session-activate path and by Mate provisioning
     * when no tool-call scope is available (the {@link top.focess.veto.group.GroupAgentFactory}
     * fallback).
     */
    public @NonNull UUID userIdForOwner(@NonNull String owner) {
        return UUID.nameUUIDFromBytes(owner.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The workspace registered for the agent (by persona id), or the fallback default. Used by the
     * group engine to inherit the calling session's workspace when spawning Mates / a one-shot
     * Leader (the calling agent's persona id is read from the {@link
     * top.focess.veto.agent.mcp.ToolCallContextHolder}).
     */
    public Workspace workspaceOf(@NonNull String agentId) {
        return hitlRegistry.workspace(agentId);
    }

    /**
     * Builds a per-session workspace from a CSV of host paths. Null/blank falls back to the default
     * (JVM working dir) to avoid re-probing the filesystem on every call.
     */
    public Workspace buildWorkspace(@Nullable String workspaceRoots) {
        if (workspaceRoots == null || workspaceRoots.isBlank()) {
            return defaultWorkspace;
        }
        return Workspace.fromConfig("", workspaceRoots, pathMode);
    }

    /**
     * The app's own config/audit paths the agent must never read (shielded under non-FULL_ACCESS).
     */
    private static List<Path> systemProtectedPaths() {
        return ProtectedSet.standardSystemProtected(Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * The protected set for an agent under the configured deployer policy: empty under FULL_ACCESS;
     * otherwise the deployer defaults (per {@code vetoUserId}) plus the app's own config/audit
     * material, so the agent cannot read {@code application.yml} / {@code config/} / {@code
     * audit/}. {@link DangerComputation} checks the set before scoping, so this applies under every
     * non-FULL_ACCESS policy. Listed as specific subpaths so a workspace nested under the launch
     * dir is unaffected.
     */
    private @NonNull ProtectedSet protectedSetFor(
            @NonNull String vetoUserId, @NonNull Workspace workspace) {
        if (this.deployerPolicy == DeployerPolicy.FULL_ACCESS) {
            return ProtectedSet.empty();
        }
        return ProtectedSet.withDeployerDefaults(vetoUserId, workspace.hostRoots())
                .withSystemProtected(systemProtectedPaths());
    }

    private static @NonNull DeployerPolicy parseDeployerPolicy(@NonNull String raw) {
        return DeployerPolicy.parse(raw);
    }

    /** Parses the screening mode (case-insensitive; defaults to STRICT on blank/unknown). */
    private static @NonNull ScreeningMode parseScreeningMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return ScreeningMode.STRICT;
        }
        for (ScreeningMode m : ScreeningMode.values()) {
            if (m.name().equalsIgnoreCase(raw.trim())) {
                return m;
            }
        }
        return ScreeningMode.STRICT;
    }
}
