package top.focess.veto.api.llm;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Host-owned MDC compilation; plugins provide a source id and bound data. */
@FunctionalInterface
public interface PromptRenderer {
    @NonNull String compile(@NonNull String source, @NonNull Map<String, Object> data);
}
