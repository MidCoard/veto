package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract for a native in-process MCP tool. The implementing class (a Java record carrying the
 * tool's structured parameters) must be annotated with {@link ToolSecurity} to declare its risk
 * category, and each parameter record component may carry a {@link SecurityHint} so the Gateway
 * knows how to screen individual arguments. Transcribed from {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md} §5.1.
 *
 * <p>The implementing record is both the parameter container and the tool bean: {@link
 * #getArgsClass()} returns the record itself, and {@link #execute(Object)} runs the tool's typed
 * logic. The {@link ToolSchemaCompiler#compileNative} factory reflects over the bean to derive its
 * {@link NativeToolDefinition} (schema + security hints) without ever instantiating it — the bean
 * instance is Spring-managed.
 *
 * @param <T> the Java record representing the tool's structured parameters
 */
public interface NativeMcpTool<T> {

    /** The unique name of the tool (e.g. {@code "view_file"}). */
    String getName();

    /** The description explaining when and how the LLM should invoke the tool. */
    String getDescription();

    /**
     * The class of the arguments container used for schema compilation and JSON deserialization.
     */
    Class<T> getArgsClass();

    /** Executes the tool logic with strongly-typed arguments. */
    String execute(T args) throws Exception;

    /**
     * Bridge method to parse raw JSON node parameters and execute the tool. Inherited automatically
     * by implementations.
     */
    default String executeFromJson(JsonNode jsonArgs, ObjectMapper mapper) throws Exception {
        T typedArgs = mapper.treeToValue(jsonArgs, getArgsClass());
        return execute(typedArgs);
    }
}
