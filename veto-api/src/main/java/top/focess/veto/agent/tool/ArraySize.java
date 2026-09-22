package top.focess.veto.agent.tool;

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
    int min();

    int max();
}
