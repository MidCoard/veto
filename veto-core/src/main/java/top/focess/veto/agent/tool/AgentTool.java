package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Contract for an agent-internal control/meta tool. The implementing class (a Java record carrying
 * the tool's structured parameters) is both the parameter container and the tool bean: {@link
 * #getArgsClass} returns the record itself, and {@link #execute(Object)} runs the tool's typed
 * logic.
 *
 * <p>Agent tools are identified by their definition flavour, so the Gateway does not path- or
 * semantic-screen them. They still flow through the LoopInterceptor chain for audit.
 *
 * <p>Registration: {@link ToolEngineImpl} discovers all {@code AgentTool<?>} beans via Spring and
 * builds an {@link AgentToolDefinition} from each via {@link AgentToolDefinition#from(Class)}.
 *
 * @param <T> the Java record representing the tool's structured parameters
 */
public interface AgentTool<T> extends LocalTool<T> {

    /** The effect boundary owned by this agent-runtime tool. */
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.AGENT_CONTROL;
    }
}
