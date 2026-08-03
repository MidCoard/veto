package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/**
 * Post-parse harness enforcement of the {@code veto_pulse} contract. Constrained decoding is
 * provider-side and not airtight; this is the backstop the loop runs after {@code
 * ObjectMapper.readValue}. A misbehaving model can neither inject forbidden reasoning into the
 * context nor silently proceed without an actionable turn.
 *
 * <ul>
 *   <li>{@code message} missing when stopping (no calls and no actions) -> {@link
 *       ModelSchemaException}.
 *   <li>Both {@code calls} and {@code actions} present -> exception (mutually exclusive).
 *   <li>{@code features} missing -> exception.
 * </ul>
 *
 * <p>{@code thought} is always optional: never required, never stripped.
 */
public final class ResponseEnforcer {

    private ResponseEnforcer() {}

    /**
     * Enforces; returns the response unchanged, or throws {@link ModelSchemaException} for a retry.
     *
     * @param guidedSwitch whether this is the guided-switch turn ({@code actions} required, {@code
     *     calls} forbidden) vs an autonomous turn.
     */
    public static @NonNull VetoResponse enforce(@NonNull VetoResponse r, boolean guidedSwitch) {
        if (r.features() == null) {
            throw new ModelSchemaException("features is required (next-status)");
        }

        // (1) calls / actions mutual exclusion (a guided-switch turn emits no calls). thought is
        // always optional and unchecked here.
        boolean hasCalls = r.hasCalls();
        boolean hasActions = r.actions() != null && r.actions().isArray() && !r.actions().isEmpty();
        if (hasCalls && hasActions) {
            throw new ModelSchemaException("calls and actions are mutually exclusive");
        }
        if (guidedSwitch && !hasActions) {
            throw new ModelSchemaException("guided-switch turn requires actions");
        }

        // (2) message required when stopping (no calls and no actions). A thought-only turn is a
        // no-op the loop cannot act on, so it is rejected here too.
        String message = r.message();
        if (!hasCalls && !hasActions && (message == null || message.isBlank())) {
            throw new ModelSchemaException(
                    "message required (no tool calls or actions to execute)");
        }

        return r;
    }
}
