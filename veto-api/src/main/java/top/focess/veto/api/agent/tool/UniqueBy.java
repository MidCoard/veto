package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/** Requires distinct values of one field across a collection of record arguments. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueBy {
    /**
     * Record field used as the uniqueness key.
     *
     * @return the element record's string field
     */
    @NonNull String field();

    /**
     * Whether keys are compared without regard to case.
     *
     * @return whether key comparison ignores case
     */
    boolean ignoreCase() default false;

    /**
     * Whether surrounding whitespace is removed before comparison.
     *
     * @return whether keys are stripped before comparison
     */
    boolean strip() default false;
}
