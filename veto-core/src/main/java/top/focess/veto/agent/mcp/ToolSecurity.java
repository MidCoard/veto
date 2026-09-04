package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/** Declares a native tool's execution capability and deterministic default danger. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolSecurity {

    /** Which execution boundary owns the tool's effect. */
    @NonNull ToolCapability capability();

    /** Danger assigned before argument-, policy-, and model-aware escalation. */
    @NonNull Danger defaultDanger();

    /** Whether this tool always requires semantic screening. */
    boolean requiresSemanticScreening() default false;
}
