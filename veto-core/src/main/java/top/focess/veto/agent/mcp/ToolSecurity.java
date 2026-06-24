package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the risk category of a native tool class (the level of danger it represents). Read by
 * the Gateway to decide screening level. Transcribed from {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md} §6.1.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolSecurity {

    /** What kind of danger this tool represents. */
    RiskCategory risk();

    /** Whether this tool always requires semantic screening regardless of risk category. */
    boolean requiresSemanticScreening() default false;
}
