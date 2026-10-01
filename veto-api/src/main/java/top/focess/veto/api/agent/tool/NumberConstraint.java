package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Inclusive numeric bounds compiled into JSON Schema and checked before tool dispatch. */
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NumberConstraint {
    /**
     * Defines the smallest accepted numeric value.
     *
     * @return inclusive minimum value
     */
    long min() default Long.MIN_VALUE;

    /**
     * Defines the largest accepted numeric value.
     *
     * @return inclusive maximum value
     */
    long max() default Long.MAX_VALUE;
}
