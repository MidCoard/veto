package top.focess.veto.agent;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.llm.core.UniformLLMCaller;

/**
 * The top orchestration layer. Creates an {@link Agent} (starting in autonomous mode by default),
 * monitors for delegation spawns ({@code create_group}), and manages the agent lifecycle.
 *
 * <p><b>Note:</b> the single-agent ReAct workflow (one agent, one prompt). Delegation spawns, the
 * Execution DAG, the Blackboard, and group governance are not implemented here; this class is the
 * structural extension point for them. The terminal/REST paths delegate to {@link AgentService}
 * directly; {@code WorkflowRunner} is the programmatic entry point.
 */
@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final McpEngine mcpEngine;
    private final HitlRegistry hitlRegistry;
    private final IngressDefense ingressDefense;
    private final PromptCompiler promptCompiler;
    private final UniformLLMCaller caller;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final java.util.List<LoopInterceptor> interceptors;
    private final java.nio.file.Path workspaceRoot;
    private final long maxCallsPerEpisode;

    public WorkflowRunner(
            McpEngine mcpEngine,
            HitlRegistry hitlRegistry,
            IngressDefense ingressDefense,
            PromptCompiler promptCompiler,
            UniformLLMCaller caller,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            java.util.List<LoopInterceptor> interceptors,
            @Value("${veto.workspace.root:}") String workspaceRoot,
            @Value("${veto.breaker.max_calls_per_episode:50}") long maxCallsPerEpisode) {
        this.mcpEngine = mcpEngine;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.interceptors = interceptors == null ? java.util.List.of() : interceptors;
        this.workspaceRoot =
                workspaceRoot == null || workspaceRoot.isBlank()
                        ? java.nio.file.Path.of(System.getProperty("user.dir", "."))
                        : java.nio.file.Path.of(workspaceRoot);
        this.maxCallsPerEpisode = maxCallsPerEpisode;
    }

    /**
     * Runs the single-agent ReAct workflow: create an agent (autonomous), submit the prompt, await
     * the result. The agent starts in autonomous mode ; guided mode is an in-loop agent decision.
     */
    public AgentResult runReact(
            AgentPersona persona, AgentRunner.LlmBinding binding, String prompt) {
        top.focess.veto.agent.drift.ReadHistory readHistory =
                new top.focess.veto.agent.drift.ReadHistory();
        Gateway gateway = new Gateway(workspaceRoot, readHistory);
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
        VetoAgent agent = new VetoAgent(persona, runner);
        agent.submit(prompt);
        try {
            return agent.await(DEFAULT_TIMEOUT);
        } catch (TimeoutException e) {
            log.warn("WorkflowRunner agent {} timed out", persona.id());
            return AgentResult.failure("Agent timed out.", java.util.Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResult.failure("Interrupted.", java.util.Map.of());
        } finally {
            agent.terminate();
        }
    }

    /** Builds a fresh anonymous persona id (for ad-hoc workflow runs). */
    public static String freshAgentId() {
        return UUID.randomUUID().toString();
    }
}
