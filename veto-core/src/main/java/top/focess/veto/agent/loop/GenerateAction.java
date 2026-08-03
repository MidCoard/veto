package top.focess.veto.agent.loop;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A model-invoked content action. The only action that calls the model — invoked within the same
 * shared conversation, with bound inputs resolved from the {@link Scope}. {@code thought} is
 * per-action (nullable → use the agent's global flag, fill-in semantics); {@code modelTier}/{@code
 * temperature} are frozen at IR-authoring time.
 */
public record GenerateAction(
        @NonNull String id,
        @NonNull String label,
        @NonNull String prompt,
        @NonNull Map<String, String> inputs,
        @NonNull Map<String, String> outputs,
        @Nullable Boolean thought,
        @Nullable String modelTier,
        @Nullable Double temperature)
        implements Action {

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

    /** Resolves {@code $var} references inside the prompt text against the scope. */
    public @NonNull String resolvePrompt(@NonNull Scope scope) {
        return scope.resolveVars(prompt);
    }
}
