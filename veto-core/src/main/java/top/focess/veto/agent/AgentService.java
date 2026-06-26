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
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ScreeningMode;
import top.focess.veto.agent.screening.SlmRelevanceProvider;
import top.focess.veto.agent.workspace.Workspace;
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

    private final ConcurrentHashMap<String, VetoAgent> agents = new ConcurrentHashMap<>();

    public AgentService(
            McpEngine mcpEngine,
            HitlRegistry hitlRegistry,
            IngressDefense ingressDefense,
            PromptCompiler promptCompiler,
            UniformLLMCaller caller,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            List<LoopInterceptor> interceptors,
            @Value("${veto.workspace.root:}") String legacyRoot,
            @Value("${veto.workspace.roots:}") String rootsCsv,
            @Value("${veto.workspace.path-mode:REAL}") String pathMode,
            @Value("${veto.breaker.max_calls_per_episode:50}") long maxCallsPerEpisode,
            @Value("${veto.security.deployer-policy:FULL_ACCESS}") String deployerPolicyRaw,
            @Value("${veto.security.screening-mode:STRICT}") String screeningModeRaw) {
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
        // Thread the runtime screening matrix to the (shared) HITL registry.
        this.hitlRegistry.setScreeningMode(parseScreeningMode(screeningModeRaw));
    }

    /**
     * Resolves (or creates) the agent for the transport id, binds the model configuration, submits
     * the prompt, and blocks for the result. Returns the {@link AgentResult}.
     */
    public AgentResult submit(String agentKey, String prompt, AgentRunner.LlmBinding binding) {
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
            String agentKey, String prompt, AgentRunner.LlmBinding binding, Duration timeout)
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
            String agentKey,
            String prompt,
            AgentRunner.LlmBinding binding,
            Duration timeout,
            Consumer<String> messageSink)
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

    /** Resolves a pending veto for an agent's call. */
    public boolean resolveVeto(
            String agentId, String callId, InterceptResolution resolution, String toolName) {
        return hitlRegistry.resolve(agentId, callId, resolution, toolName);
    }

    /** The live agent for a transport id (for history / state inspection). */
    public VetoAgent agent(String agentKey) {
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
                        binding);
        return new VetoAgent(persona, runner);
    }

    /**
     * Builds the agent persona. MVP: a default persona carrying the resolved (empty for the live
     * terminal path) manifest + skills. Full {@code ~/.veto/} persona resolution is not yet wired.
     */
    private AgentPersona buildPersona(String agentKey, AgentRunner.LlmBinding binding) {
        return new AgentPersona(
                UUID.randomUUID().toString(),
                "agent-" + agentKey,
                "Veto agent",
                Set.of(),
                List.of());
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
