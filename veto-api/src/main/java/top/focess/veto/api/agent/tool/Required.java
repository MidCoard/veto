package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a primitive tool argument as present in every valid call.
 *
 * <p>Nullability annotations do not apply to primitives, so a primitive component must carry this
 * annotation to be added to the schema's {@code required} list; use {@code @NonNull} instead on any
 * reference-typed component. The host rejects a primitive component without it and a reference
 * component that carries it.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Required {}
