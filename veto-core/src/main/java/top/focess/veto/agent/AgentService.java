package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ScreeningMode;
import top.focess.veto.agent.screening.SlmRelevanceProvider;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.bus.DeltaBroker;
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

    private final McpEngine mcpEngine;
    private final HitlRegistry hitlRegistry;
    private final IngressDefense ingressDefense;
    private final PromptCompiler promptCompiler;
    private final UniformLLMCaller caller;
    private final ObjectMapper objectMapper;
    private final List<LoopInterceptor> interceptors;
    private final Workspace workspace;
    private final long maxCallsPerEpisode;
    private final DeployerPolicy deployerPolicy;
    private final ProtectedSet protectedSet;
    // The Part-8 Delta-broker — optional (nullable in tests); when present, threaded into each
    // AgentRunner so loop emissions publish per-session DeltaFrames for transports to stream.
    @Nullable private final DeltaBroker deltaBroker;
    // The Part-4 per-turn memory-capture service — optional (nullable in tests); when present,
    // threaded into each AgentRunner so appendTurn captures into Session LTM + the raw-turn log.
    @Nullable private final top.focess.veto.memory.MemoryCaptureService captureService;

    /**
     * The default user id for memory capture. Per-user identity is not yet wired at the transport
     * ({@code submit} takes no user id), so all local-CLI agents capture under this single user
     * until the transport passes a real user id. Multi-user isolation is the follow-up.
     */
    static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ConcurrentHashMap<String, VetoAgent> agents = new ConcurrentHashMap<>();

    public AgentService(
            @NotNull McpEngine mcpEngine,
            @NotNull HitlRegistry hitlRegistry,
            @NotNull IngressDefense ingressDefense,
            @NotNull PromptCompiler promptCompiler,
            @NotNull UniformLLMCaller caller,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NotNull ObjectMapper objectMapper,
            @Nullable List<LoopInterceptor> interceptors,
            @Value("${veto.workspace.root:}") @NotNull String legacyRoot,
            @Value("${veto.workspace.roots:}") @NotNull String rootsCsv,
            @Value("${veto.workspace.path-mode:REAL}") @NotNull String pathMode,
            @Value("${veto.breaker.max_calls_per_episode:50}") long maxCallsPerEpisode,
            @Value("${veto.security.deployer-policy:FULL_ACCESS}") @NotNull
                    String deployerPolicyRaw,
            @Value("${veto.security.screening-mode:STRICT}") @NotNull String screeningModeRaw,
            @Nullable DeltaBroker deltaBroker,
            @Nullable top.focess.veto.memory.MemoryCaptureService captureService) {
        this.mcpEngine = mcpEngine;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.workspace = Workspace.fromConfig(legacyRoot, rootsCsv, pathMode);
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        this.deployerPolicy = parseDeployerPolicy(deployerPolicyRaw);
        this.protectedSet =
                this.deployerPolicy == DeployerPolicy.PROTECTED
                        ? ProtectedSet.withDeployerDefaults(this.workspace.hostRoots())
                        : ProtectedSet.empty();
        // Thread the runtime screening matrix + workspace to the (shared) HITL registry. The
        // workspace is needed for canonical-path arg extraction when matching permission grants.
        this.hitlRegistry.setScreeningMode(parseScreeningMode(screeningModeRaw));
        this.hitlRegistry.setWorkspace(this.workspace);
        this.deltaBroker = deltaBroker;
        this.captureService = captureService;
    }

    /**
     * Resolves (or creates) the agent for the transport id, binds the model configuration, submits
     * the prompt, and blocks for the result. Returns the {@link AgentResult}.
     */
    public AgentResult submit(
            @NotNull String agentKey,
            @NotNull String prompt,
            @NotNull AgentRunner.LlmBinding binding) {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        agent.submit(prompt);
        try {
            return agent.await(DEFAULT_AWAIT);
        } catch (TimeoutException e) {
            log.warn("Agent {} await timed out", agentKey);
            return AgentResult.failure("Agent timed out.", Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResult.failure("Interrupted.", Map.of());
        }
    }

    /** Synchronous submit with a live result message (for the terminal path). */
    public AgentResult submit(
            @NotNull String agentKey,
            @NotNull String prompt,
            @NotNull AgentRunner.LlmBinding binding,
            @NotNull Duration timeout)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
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
            @NotNull String agentKey,
            @NotNull String prompt,
            @NotNull AgentRunner.LlmBinding binding,
            @NotNull Duration timeout,
            @Nullable Consumer<String> messageSink)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding));
        agent.bind(binding);
        if (messageSink != null) {
            agent.addMessageListener(messageSink);
        }
        try {
            agent.submit(prompt);
            return agent.await(timeout);
        } finally {
            if (messageSink != null) {
                agent.removeMessageListener(messageSink);
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
            @NotNull String agentKey,
            @NotNull String prompt,
            @NotNull AgentRunner.LlmBinding binding,
            @NotNull Duration timeout,
            @NotNull UUID userId)
            throws TimeoutException, InterruptedException {
        VetoAgent agent = agents.computeIfAbsent(agentKey, k -> createAgent(k, binding, userId));
        agent.bind(binding);
        agent.submit(prompt);
        return agent.await(timeout);
    }

    /**
     * Resolves a pending veto for an agent's call. The {@code toolName} parameter is kept for
     * back-compat with the old API (the new {@code HitlRegistry.resolve} reads the tool name from
     * the supplied {@link ToolCall}). The caller can pass {@code null} for the call/def when the
     * original call is not available (e.g. legacy veto endpoints).
     */
    public boolean resolveVeto(
            @NotNull String agentId,
            @NotNull String callId,
            @NotNull InterceptResolution resolution,
            @NotNull String toolName,
            @Nullable top.focess.veto.llm.core.ToolCall originalCall,
            @Nullable top.focess.veto.agent.mcp.ToolDefinition originalDef) {
        return hitlRegistry.resolve(agentId, callId, resolution, originalCall, originalDef);
    }

    /** The live agent for a transport id (for history / state inspection). */
    @Nullable
    public VetoAgent agent(@NotNull String agentKey) {
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
    public void remove(String agentKey) {
        VetoAgent a = agents.remove(agentKey);
        if (a != null) {
            a.terminate();
        }
    }

    private VetoAgent createAgent(String agentKey, AgentRunner.LlmBinding binding) {
        return createAgent(agentKey, binding, DEFAULT_USER_ID);
    }

    private VetoAgent createAgent(
            @NotNull String agentKey,
            @NotNull AgentRunner.LlmBinding binding,
            @NotNull UUID userId) {
        AgentPersona persona = buildPersona(agentKey, binding);
        ReadHistory readHistory = new ReadHistory();
        ProtectedSet userProtectedSet =
                this.deployerPolicy == DeployerPolicy.PROTECTED
                        ? ProtectedSet.withDeployerDefaults(agentKey, this.workspace.hostRoots())
                        : ProtectedSet.empty();
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
                        captureService);
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
        return createMate(persona, binding, DEFAULT_USER_ID);
    }

    /**
     * Builds a Mate {@link Agent} with explicit user identity for multi-user tenant isolation. The
     * Mate inherits the Leader's userId so its memory capture is scoped to the same tenant.
     */
    public Agent createMate(
            @NotNull AgentPersona persona,
            @NotNull AgentRunner.LlmBinding binding,
            @NotNull UUID userId) {
        ReadHistory readHistory = new ReadHistory();
        Gateway gateway =
                new Gateway(
                        workspace,
                        new DangerComputation(),
                        SlmRelevanceProvider.degraded(),
                        deployerPolicy,
                        ProtectedSet.empty(),
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
                        captureService);
        return new VetoAgent(persona, runner);
    }

    /**
     * Builds the agent persona. Resolves the tool whitelist from the {@link McpEngine}'s active
     * native + remote tools (agent tools like {@code load_skill} are always-on, runtime-excluded
     * from the stored set per {@link AgentPersona}). Full {@code ~/.veto/} persona resolution
     * (skills, per-agent tool grants) is not yet wired — the default grants all registered tools.
     */
    private AgentPersona buildPersona(String agentKey, AgentRunner.LlmBinding binding) {
        Set<ToolDefinition> tools =
                mcpEngine.getActiveTools(null).stream()
                        .filter(d -> !(d instanceof AgentToolDefinition))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AgentPersona(
                UUID.randomUUID().toString(), "agent-" + agentKey, "Veto agent", tools, List.of());
    }

    /** The workspace agents resolve paths against (for tests). */
    public Workspace workspace() {
        return workspace;
    }

    private static DeployerPolicy parseDeployerPolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeployerPolicy.FULL_ACCESS;
        }
        for (DeployerPolicy p : DeployerPolicy.values()) {
            if (p.name().equalsIgnoreCase(raw.trim())) {
                return p;
            }
        }
        throw new IllegalArgumentException(
                "Unknown veto.security.deployer-policy '"
                        + raw
                        + "'; expected one of "
                        + java.util.Arrays.toString(DeployerPolicy.values()));
    }

    /** Parses the screening mode (case-insensitive; defaults to STRICT on blank/unknown). */
    private static ScreeningMode parseScreeningMode(String raw) {
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
