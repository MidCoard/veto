package top.focess.veto.agent.loop;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A fully-bound deterministic tool action. Runs with <b>no model call</b> — the harness has the
 * tool name, bound inputs, and output bindings.
 */
public record ToolAction(
        @NonNull String id,
        @NonNull String label,
        @NonNull String tool,
        @NonNull Map<String, String> inputs,
        @NonNull Map<String, String> outputs)
        implements Action {

    public ToolAction {
        if (tool.isBlank()) {
            throw new IllegalArgumentException("tool action requires a tool name");
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
}
