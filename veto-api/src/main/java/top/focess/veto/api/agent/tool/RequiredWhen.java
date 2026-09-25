package top.focess.veto.api.agent.tool;

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

    /**
     * Names the discriminator that controls this requirement.
     *
     * @return name of the sibling record component that selects the applicable variant
     */
    @NonNull String field();

    /**
     * Lists the discriminator values that activate this requirement.
     *
     * @return serialized discriminator values that require the annotated component
     */
    @NonNull String @NonNull [] values();

    /**
     * Controls whether whitespace-only text counts as missing.
     *
     * @return whether textual values containing only whitespace are rejected centrally
     */
    boolean rejectBlank() default false;
}
