package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The universal ReAct response record—every model response conforms to this shape. Transcribed from
 * (the {@code veto_pulse} schema).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code thought}—optional; the model's internal reasoning before acting. Never required,
 *       never forbidden: include it when it helps, omit it when it does not.
 *   <li>{@code calls}—optional; the ordered tool calls to execute. Mutually exclusive with {@code
 *       actions}. Populated in autonomous mode.
 *   <li>{@code message}—optional response text; required when stopping (no calls and no actions).
 *   <li>{@code features}—required; describes the NEXT iteration's status ({@code guided}).
 *   <li>{@code actions}—optional; the guided-mode IR (a flat, ordered list of actions), present
 *       only when {@code features.guided=true}. Held as a raw {@link JsonNode}—the harness
 *       validates and parses it into the guided driver's typed program; the translator emits its
 *       schema.
 * </ul>
 *
 * <p>Per-turn schema variants and harness enforcement are the {@code PromptCompiler} / loop's
 * concern; the provider-constraining schema is the {@code CapabilityTranslator}'s concern. This
 * record is the shared contract both compile against.
 */
public record VetoResponse(
        String thought,
        List<@NonNull ToolCall> calls,
        String message,
        Features features,
        JsonNode actions) {

    /** Convenience: whether this response carries any tool calls. */
    @JsonIgnore
    public boolean hasCalls() {
        return calls != null && !calls.isEmpty();
    }

    /**
     * The NEXT-iteration status. Always present in a compliant response. {@code guided} selects
     * guided vs autonomous for the next iteration.
     */
    public record Features(@JsonProperty("guided") boolean guided) {}
}
