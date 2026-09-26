package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Array cardinality bounds for one tool argument.
 *
 * <p>The host compiles these into the tool's JSON Schema ({@code minItems}/{@code maxItems}) and
 * enforces them against every call before the tool body runs, so a tool must not re-check the size.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArraySize {
    /**
     * Sets the lower cardinality bound.
     *
     * @return minimum accepted array length, inclusive
     */
    int min();

    /**
     * Sets the upper cardinality bound.
     *
     * @return maximum accepted array length, inclusive
     */
    int max();
}
