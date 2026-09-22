package top.focess.veto.agent.tool;

import java.lang.annotation.*;

/**
 * Explicit schema for records with a discriminated parameter language. Runtime validation still
 * applies.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolInputSchema {
    @org.jspecify.annotations.NonNull
    Class<? extends InputSchemaSource> value();
}
