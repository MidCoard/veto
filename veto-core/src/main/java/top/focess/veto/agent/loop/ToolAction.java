package top.focess.veto.agent.loop;

import java.util.HashMap;
import java.util.Map;

/**
 * A fully-bound deterministic tool action (LLD {@code workflow_execution_engine.md} §3.1). Runs
 * with <b>no model call</b> — the harness has the tool name, bound inputs, and output bindings.
 */
public record ToolAction(
        String id,
        String label,
        String tool,
        Map<String, String> inputs,
        Map<String, String> outputs)
        implements Action {

    public ToolAction {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("tool action requires a tool name");
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
}
