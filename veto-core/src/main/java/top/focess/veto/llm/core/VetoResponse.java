package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The universal ReAct response record — every model response conforms to this shape. Transcribed
 * from (the {@code veto_pulse} schema).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code thought} — optional; strictly controlled by this turn's effective thought flag (ON →
 *       present &amp; non-empty; OFF → absent, forbidden by {@code additionalProperties:false}).
 *   <li>{@code calls} — optional; the parallel tool calls to execute. Mutually exclusive with
 *       {@code actionsProgram}. Populated in autonomous mode.
 *   <li>{@code message} — optional user-facing text; required when thought is OFF or when finished.
 *   <li>{@code is_finished} — required; true when the model cannot proceed without user input.
 *   <li>{@code features} — required; describes the NEXT iteration's status ({@code guided}, {@code
 *       thought}).
 *   <li>{@code actionsProgram} — optional; the guided-mode IR, present only when {@code
 *       features.guided=true}. Held as a raw {@link JsonNode} — the harness validates and parses it
 *       into the guided driver's typed program; the translator emits its schema.
 * </ul>
 *
 * <p>Per-turn schema variants and harness enforcement are the {@code PromptCompiler} / loop's
 * concern; the provider-constraining schema is the {@code CapabilityTranslator}'s concern. This
 * record is the shared contract both compile against.
 */
public record VetoResponse(
        String thought,
        List<ToolCall> calls,
        String message,
        @JsonProperty("is_finished") boolean isFinished,
        Features features,
        JsonNode actionsProgram) {

    /** Convenience: whether this response carries any tool calls. */
    @JsonIgnore
    public boolean hasCalls() {
        return calls != null && !calls.isEmpty();
    }

    /**
     * The NEXT-iteration status. Always present in a compliant response. {@code guided} selects
     * guided vs autonomous for the next iteration; {@code thought} sets the effective thought flag
     * for the following turn..
     */
    public record Features(
            @JsonProperty("guided") boolean guided, @JsonProperty("thought") boolean thought) {}
}
