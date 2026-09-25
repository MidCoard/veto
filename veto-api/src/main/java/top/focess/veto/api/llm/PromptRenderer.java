package top.focess.veto.api.llm;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Host-owned MDC compilation; plugins provide a source id and bound data. */
@FunctionalInterface
public interface PromptRenderer {
    /**
     * Compiles one host-owned MDC resource with structured bindings.
     *
     * @param source MDC resource identifier
     * @param data immutable prompt bindings
     * @return compiled prompt text
     */
    @NonNull String compile(@NonNull String source, @NonNull Map<String, Object> data);
}
