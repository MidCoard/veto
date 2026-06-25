package top.focess.veto.agent.intercept;

import top.focess.veto.agent.mcp.McpToolResult;
import top.focess.veto.llm.core.ToolCall;

/**
 * A user-plugin interceptor in the ordered chain the {@code AgentRunner} iterates at three join
 * points. This chain is <b>plugins only</b> — it transforms, observes, or blocks; it does
 * <b>not</b> perform security screening and does <b>not</b> signal HITL. Security screening is the
 * {@link Gateway}'s job (producing a {@link GatewayResult}) and HITL pausing is the {@link
 * HitlRegistry}'s job; both happen <i>before</i> this chain for native/remote tools. Agent tools
 * early-route past both but still pass this chain if plugins are registered.
 */
public interface LoopInterceptor {

    /**
     * Fired before a tool executes (after the Gateway has screened and HITL has approved, for
     * native/remote tools; after early-route for agent tools). A plugin may transform the call or
     * block it (return {@code false}). Blocking is the plugin's own prerogative — it does NOT
     * trigger a HITL pause; the call is simply refused with an error observation.
     *
     * @return {@code true} to continue to the next plugin / proceed to execution; {@code false} to
     *     block the call.
     */
    boolean preAction(String agentId, ToolCall call);

    /**
     * Fired after tool execution but before the observation is masked/framed. Each plugin may
     * transform or redact the result.
     */
    McpToolResult postAction(String agentId, ToolCall call, McpToolResult result);

    /**
     * Fired before an observation enters the prompt compiler (after ingress defense has
     * framed/masked it). Each plugin may tag, frame, or redact.
     */
    String preObservation(String agentId, String rawObservation);
}
