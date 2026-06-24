package top.focess.veto.llm.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code veto_pulse} response schema as a plain {@link Map} — the new universal {@link
 * top.focess.veto.llm.core.VetoResponse} shape (LLD {@code prompt_react_syntax.md} §2.1). Used as
 * the providers' fallback when a {@link top.focess.veto.llm.core.VetoRequest} carries no per-turn
 * {@code responseSchema} (stray direct calls); the loop always sets one via the {@link
 * top.focess.veto.agent.translation.CapabilityTranslator}. This retires the old single-{@code call}
 * {@code SchemaNormalizerService} veto_pulse builders.
 */
public final class VetoPulseSchema {

    private VetoPulseSchema() {}

    /** The default variant: thought-ON, autonomous (calls allowed, actionsProgram forbidden). */
    public static Map<String, Object> defaultAutonomousThoughtOn() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("thought", orderedMap("type", "string"));
        properties.put(
                "calls",
                orderedMap(
                        "type",
                        "array",
                        "items",
                        orderedMap(
                                "type",
                                "object",
                                "properties",
                                orderedMap(
                                        "tool_name", orderedMap("type", "string"),
                                        "args", orderedMap("type", "object")),
                                "required",
                                List.of("tool_name", "args"),
                                "additionalProperties",
                                false)));
        properties.put("message", orderedMap("type", "string"));
        properties.put("is_finished", orderedMap("type", "boolean"));
        properties.put(
                "features",
                orderedMap(
                        "type",
                        "object",
                        "properties",
                        orderedMap(
                                "guided", orderedMap("type", "boolean"),
                                "thought", orderedMap("type", "boolean")),
                        "required",
                        List.of("guided", "thought"),
                        "additionalProperties",
                        false));
        return orderedMap(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.of("is_finished", "features", "thought"),
                "additionalProperties",
                false);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
