package top.focess.veto.api.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/** MDC system instruction source compiled only while the annotated tool is available. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPrompt {
    /**
     * Selects the prompt resource associated with the tool.
     *
     * @return MDC resource identifier compiled while the tool is available
     */
    @NonNull String value();
}
