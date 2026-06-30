package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/**
 * Post-parse harness enforcement of the {@code veto_pulse} contract ( ). Constrained decoding is
 * provider-side and not airtight; this is the backstop the loop runs after {@code
 * ObjectMapper.readValue}. A misbehaving model can neither inject forbidden reasoning into the
 * context nor silently proceed without the reasoning the rule requires.
 *
 * <ul>
 *   <li>Effective thought <b>ON</b> but {@code thought} missing/empty → {@link
 *       ModelSchemaException}.
 *   <li>Effective thought <b>OFF</b> but {@code thought} present → <b>strip</b> it (deterministic
 *       drop).
 *   <li>{@code message} missing when required (thought OFF, or {@code is_finished}) → exception.
 *   <li>Both {@code calls} and {@code actionsProgram} present → exception (mutually exclusive).
 *   <li>Empty turn (no thought/message/calls/program) → exception.
 *   <li>{@code features} missing → exception.
 * </ul>
 */
public final class ResponseEnforcer {

    private ResponseEnforcer() {}

    /** Enforces; returns the (possibly thought-stripped) response, or throws for a retry. */
    public static @NonNull VetoResponse enforce(
            @NonNull VetoResponse r, boolean effectiveThought, boolean guidedSwitch) {
        if (r == null) {
            throw new ModelSchemaException("null VetoResponse");
        }
        if (r.features() == null) {
            throw new ModelSchemaException("features is required (next-status)");
        }

        String thought = r.thought();
        // (1) thought control.
        if (effectiveThought) {
            if (thought == null || thought.isBlank()) {
                throw new ModelSchemaException("effective thought ON but 'thought' missing/empty");
            }
        } else {
            if (thought != null && !thought.isBlank()) {
                thought = null; // strip forbidden reasoning
            }
        }

        // (2) calls / actionsProgram mutual exclusion (a guided-switch turn emits no calls).
        boolean hasCalls = r.hasCalls();
        boolean hasProgram = r.actionsProgram() != null && !r.actionsProgram().isNull();
        if (hasCalls && hasProgram) {
            throw new ModelSchemaException("calls and actionsProgram are mutually exclusive");
        }
        if (guidedSwitch && !hasProgram) {
            throw new ModelSchemaException("guided-switch turn requires actionsProgram");
        }

        // (3) message required when thought OFF or finished.
        boolean messageRequired = !effectiveThought || r.isFinished();
        String message = r.message();
        if (messageRequired && (message == null || message.isBlank())) {
            // is_finished with calls is contradictory — require a message either way.
            throw new ModelSchemaException("message required (thought OFF or is_finished)");
        }

        // (4) no empty turns.
        boolean empty =
                (thought == null || thought.isBlank())
                        && (message == null || message.isBlank())
                        && !hasCalls
                        && !hasProgram;
        if (empty) {
            throw new ModelSchemaException("empty turn — no thought/message/calls/actionsProgram");
        }

        if (thought == null && r.thought() != null) {
            // reconstruct with stripped thought
            return new VetoResponse(
                    null, r.calls(), r.message(), r.isFinished(), r.features(), r.actionsProgram());
        }
        return r;
    }
}
