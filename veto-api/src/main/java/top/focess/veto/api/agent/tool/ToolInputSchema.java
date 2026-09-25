package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;

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
    @org.jspecify.annotations.NonNull
    Class<? extends InputSchemaSource> value();
}
