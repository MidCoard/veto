package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

/**
 * Contract for a native in-process MCP tool. The implementing class (a Java record carrying the
 * tool's structured parameters) must be annotated with {@link ToolSecurity} to declare its risk
 * category, and each parameter record component may carry a {@link SecurityHint} so the Gateway
 * knows how to screen individual arguments.
 *
 * <p>The implementing record is both the parameter container and the tool bean: {@link
 * #getArgsClass} returns the record itself, and {@link #execute(Object)} runs the tool's typed
 * logic. The {@link ToolSchemaCompiler#compileNative} factory reflects over the bean to derive its
 * {@link NativeToolDefinition} (schema + security hints) without ever instantiating it — the bean
 * instance is Spring-managed.
 *
 * @param <T> the Java record representing the tool's structured parameters
 */
public interface NativeMcpTool<T> {

    /** The unique name of the tool (e.g. {@code "view_file"}). */
    @NonNull String getName();

    /** The description explaining when and how the LLM should invoke the tool. */
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
