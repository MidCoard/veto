package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * The parameter contract for a tool. Two flavours.
 *
 * <ul>
 *   <li>{@link Structured} — native tool, backed by a Java record. Schema and security hints are
 *       derived from annotations via reflection.
 *   <li>{@link Raw} — external MCP tool, backed by raw JSON Schema from server discovery. No
 *       compile-time type info; the Gateway applies maximum scrutiny.
 * </ul>
 */
public sealed interface ParameterSchema permits ParameterSchema.Structured, ParameterSchema.Raw {

    /**
     * Strongly-typed native parameters.
     *
     * @param argsClass the Java record class used for JSON deserialization and execution
     * @param hints param name → {@link ParamCategory}, derived from {@code @SecurityHint}
     */
    record Structured(Class<?> argsClass, Map<String, ParamCategory> hints)
            implements ParameterSchema {}

    /**
     * Raw JSON Schema from an external MCP server. No parameter-level hints; the Gateway treats
     * every parameter as {@link ParamCategory#GENERIC}.
     */
    record Raw(JsonNode jsonSchema) implements ParameterSchema {}
}
