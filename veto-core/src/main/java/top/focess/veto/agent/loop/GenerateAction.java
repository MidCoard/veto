package top.focess.veto.agent.loop;

import java.util.HashMap;
import java.util.Map;

/**
 * A model-invoked content action. The only action that calls the model — invoked within the same
 * shared conversation, with bound inputs resolved from the {@link Scope}. {@code thought} is
 * per-action (nullable → use the agent's global flag, fill-in semantics); {@code modelTier}/{@code
 * temperature} are frozen at IR-authoring time.
 */
public record GenerateAction(
        String id,
        String label,
        String prompt,
        Map<String, String> inputs,
        Map<String, String> outputs,
        Boolean thought,
        String modelTier,
        Double temperature)
        implements Action {

    public GenerateAction {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("generate action requires a prompt");
        }
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    @Override
    public Map<String, Object> resolveInputs(Scope scope) {
        Map<String, Object> resolved = new HashMap<>();
        for (var entry : inputs.entrySet()) {
            resolved.put(entry.getKey(), scope.resolveValue(entry.getValue()));
        }
        return resolved;
    }

    /** Resolves {@code $var} references inside the prompt text against the scope. */
    public String resolvePrompt(Scope scope) {
        return scope.resolveVars(prompt);
    }
}
