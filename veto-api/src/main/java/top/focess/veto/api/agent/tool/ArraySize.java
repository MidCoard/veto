package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Array cardinality advertised in every provider's tool schema; runtime validation remains
 * required.
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
