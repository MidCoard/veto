package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;

/** Declares a native tool's execution capability and deterministic default danger. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolSecurity {

    /**
     * Selects the authority boundary for the tool's effects.
     *
     * @return the execution boundary that owns the tool's effect
     */
    @NonNull ToolCapability capability();

    /**
     * Establishes the minimum deterministic risk classification.
     *
     * @return danger assigned before argument-, policy-, and model-aware escalation
     */
    @NonNull Danger defaultDanger();

    /**
     * Requires model-assisted screening even when deterministic checks are satisfied.
     *
     * @return whether this tool always requires semantic screening
     */
    boolean requiresSemanticScreening() default false;
}
