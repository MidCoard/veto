package top.focess.veto.agent.loop;

import java.util.Set;
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
        return enforce(r, guidedSwitch, Set.of());
    }

    /**
     * Enforces the response contract and, when supplied, the exact role-scoped tool-name set. An
     * empty set retains the compatibility behavior used by schema-only callers.
     */
    public static @NonNull VetoResponse enforce(
            @NonNull VetoResponse r,
            boolean guidedSwitch,
            @NonNull Set<@NonNull String> allowedToolNames) {
        var features = r.features();
        if (features == null) {
            throw new ModelSchemaException("features is required (next-status)");
        }

        // (1) calls / actions mutual exclusion (a guided-switch turn emits no calls). thought is
        // always optional and unchecked here.
        var calls = r.calls();
        boolean hasCalls = calls != null && !calls.isEmpty();
        var actions = r.actions();
        boolean hasActions = actions != null && actions.isArray() && !actions.isEmpty();
        if (hasCalls && hasActions) {
            throw new ModelSchemaException("calls and actions are mutually exclusive");
        }
        if (calls != null && !calls.isEmpty() && !allowedToolNames.isEmpty()) {
            for (var call : calls) {
                String name = call.toolName();
                if (!allowedToolNames.contains(name)) {
                    throw new ModelSchemaException(
                            "calls[].tool_name must exactly name a catalog tool; '"
                                    + name
                                    + "' is not in this turn's tool catalog");
                }
            }
        }
        if (guidedSwitch && !hasActions) {
            throw new ModelSchemaException("guided-switch turn requires actions");
        }
        if (guidedSwitch && !features.guided()) {
            throw new ModelSchemaException("guided-switch turn requires features.guided=true");
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
