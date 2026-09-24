package top.focess.veto.builtin.planning;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A model-invoked content action. The only action that calls the model — invoked within the same
 * shared conversation, with bound inputs resolved from the {@link Scope}. {@code thought} is a
 * legacy per-action recording preference, not a provider thinking-mode switch; {@code
 * modelTier}/{@code temperature} are frozen at IR-authoring time.
 */
public record GenerateAction(
        @NonNull String id,
        @NonNull String label,
        @NonNull String prompt,
        @NonNull Map<String, Object> inputs,
        @NonNull Map<String, String> outputs,
        Boolean thought,
        String modelTier,
        Double temperature,
        @NonNull ResponseMode responseMode)
        implements Action {

    public enum ResponseMode {
        TEXT,
        CITATIONS
    }

    /** Existing programs generate ordinary text unless they request source-linked output. */
    public GenerateAction(
            @NonNull String id,
            @NonNull String label,
            @NonNull String prompt,
            @NonNull Map<String, Object> inputs,
            @NonNull Map<String, String> outputs,
            Boolean thought,
            String modelTier,
            Double temperature) {
        this(
                id,
                label,
                prompt,
                inputs,
                outputs,
                thought,
                modelTier,
                temperature,
                ResponseMode.TEXT);
    }

    public GenerateAction {
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("generate action requires a prompt");
        }
        inputs = Map.copyOf(inputs);
        outputs = Map.copyOf(outputs);
    }

    @Override
    public @NonNull Map<String, @NonNull Object> resolveInputs(@NonNull Scope scope) {
        Map<String, Object> resolved = new HashMap<>();
        for (var entry : inputs.entrySet()) {
            resolved.put(entry.getKey(), scope.resolveValue(entry.getValue()));
        }
        return resolved;
    }

    /**
     * Resolves optional {@code $var} substitutions in the prompt. Resolved inputs are also supplied
     * separately to the invocation, so an input need not appear as a prompt placeholder.
     */
    public @NonNull String resolvePrompt(@NonNull Scope scope) {
        Scope local = scope.child();
        resolveInputs(scope).forEach(local::put);
        return local.resolveVars(prompt);
    }
}
