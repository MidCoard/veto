package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

/**
 * Contract for an agent-internal control/meta tool. The implementing class (a Java record carrying
 * the tool's structured parameters) is both the parameter container and the tool bean: {@link
 * #getArgsClass} returns the record itself, and {@link #execute(Object)} runs the tool's typed
 * logic.
 *
 * <p>Agent tools carry {@link RiskCategory#AGENT} — the Gateway does not path/semantic-screen them.
 * They still flow through the LoopInterceptor chain for audit.
 *
 * <p>Registration: {@link ToolEngineImpl} discovers all {@code AgentTool<?>} beans via Spring and
 * builds an {@link AgentToolDefinition} from each via {@link AgentToolDefinition#from(Class)}.
 *
 * @param <T> the Java record representing the tool's structured parameters
 */
public interface AgentTool<T> {

    /** The unique name of the tool (e.g. {@code "think"}). */
    @NonNull String getName();

    /** The one-liner description — what the tool is. */
    @NonNull String getDescription();

    /**
     * The class of the arguments container used for schema compilation and JSON deserialization.
     */
    @NonNull Class<T> getArgsClass();

    /** Executes the tool logic with strongly-typed arguments. */
    @NonNull String execute(@NonNull T args) throws Exception;

    /**
     * Bridge method to parse raw JSON node parameters and execute the tool. Inherited automatically
     * by implementations.
     */
    @NonNull
    default String executeFromJson(@NonNull JsonNode jsonArgs, @NonNull ObjectMapper mapper)
            throws Exception {
        T typedArgs = mapper.treeToValue(jsonArgs, getArgsClass());
        return execute(typedArgs);
    }
}
