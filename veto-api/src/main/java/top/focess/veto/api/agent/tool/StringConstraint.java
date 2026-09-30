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
 * schema cannot express without inspecting sibling values or collection entries.
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

    /**
     * Whether whitespace-only values are rejected after decoding.
     *
     * @return whether blank strings are invalid
     */
    boolean rejectBlank() default false;

    /**
     * Literal values that are not accepted after surrounding whitespace is stripped.
     *
     * @return reserved string values
     */
    @NonNull String @NonNull [] forbidden() default {};

    /**
     * Whether forbidden values are compared without regard to case.
     *
     * @return whether case is ignored for forbidden values
     */
    boolean forbiddenIgnoreCase() default false;
}
