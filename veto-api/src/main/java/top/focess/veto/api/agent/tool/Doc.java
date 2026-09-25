package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing description for a native tool parameter record component. Reflected at load time by
 * the host's tool-schema compiler into the parameter's {@code description} in the generated JSON
 * Schema.
 *
 * <p>Declared as a top-level type so it can be applied ergonomically to record components.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Doc {

    /**
     * Supplies the parameter description placed in the generated schema.
     *
     * @return the model-visible parameter description
     */
    @NonNull String value();
}
