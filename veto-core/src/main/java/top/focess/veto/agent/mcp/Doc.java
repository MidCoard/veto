package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing description for a native tool parameter record component. Reflected at load time by
 * {@link ToolSchemaCompiler} into the parameter's {@code description} in the generated JSON Schema.
 * .
 *
 * <p>The nests this annotation inside {@code ToolSchemaCompiler}; it is promoted to a top-level
 * type here so it can be applied ergonomically to record components (a top-level annotation is the
 * conventional placement — an obviously-unspecified detail noted per the implementation charter).
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Doc {

    @NonNull String value();
}
