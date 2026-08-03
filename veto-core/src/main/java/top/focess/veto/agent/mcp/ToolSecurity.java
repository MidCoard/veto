package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * Declares the risk category of a native tool class (the level of danger it represents). Read by
 * the Gateway to decide screening level..
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolSecurity {

    /** What kind of danger this tool represents. */
    @NonNull RiskCategory risk();

    /** Whether this tool always requires semantic screening regardless of risk category. */
    boolean requiresSemanticScreening() default false;
}
