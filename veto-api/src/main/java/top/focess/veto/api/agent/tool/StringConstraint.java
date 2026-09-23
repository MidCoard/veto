package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/** String constraints advertised in tool schemas; runtime validation remains required. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface StringConstraint {
    int minLength() default 0;

    int maxLength() default Integer.MAX_VALUE;

    @NonNull String pattern() default "";
}
