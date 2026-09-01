package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * Declares that an otherwise optional record component is required for selected values of a sibling
 * discriminator field.
 *
 * <p>The shared argument validator enforces this contract before deserialization and tool dispatch.
 * Tool handlers may therefore implement business behavior without repeating missing argument
 * checks.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface RequiredWhen {

    /** Name of the sibling record component that selects the applicable variant. */
    @NonNull String field();

    /** Serialized discriminator values for which the annotated component is required. */
    @NonNull String @NonNull [] values();

    /** Whether a textual value containing only whitespace is also rejected centrally. */
    boolean rejectBlank() default false;
}
