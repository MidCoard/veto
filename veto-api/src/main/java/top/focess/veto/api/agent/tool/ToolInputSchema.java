package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;
import org.jspecify.annotations.NonNull;

/**
 * Explicit schema for records with a discriminated parameter language. Runtime validation still
 * applies.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolInputSchema {
    /**
     * Selects the source of the complete schema.
     *
     * @return schema source instantiated by the host for the annotated argument type
     */
    @NonNull Class<? extends InputSchemaSource> value();
}
