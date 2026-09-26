package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * String length and pattern bounds for one tool argument.
 *
 * <p>The host compiles these into the tool's JSON Schema and enforces them against every call
 * before the tool body runs, so a tool must not re-check them. Implement only constraints the
 * schema cannot express, such as cross-field uniqueness or a reserved value.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface StringConstraint {
    /**
     * Sets the lower length bound.
     *
     * @return minimum accepted string length, inclusive
     */
    int minLength() default 0;

    /**
     * Sets the upper length bound.
     *
     * @return maximum accepted string length, inclusive
     */
    int maxLength() default Integer.MAX_VALUE;

    /**
     * Optionally constrains the complete string with a regular expression.
     *
     * @return regular expression required of the value, or empty when unconstrained
     */
    @NonNull String pattern() default "";
}
