package top.focess.veto.agent.intercept;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * The user's resolution of a veto pause, delivered via the veto endpoint and used to complete the
 * parked {@link java.util.concurrent.CompletableFuture}. For {@link VetoOption#EDIT} the user
 * rewrites the call's args; the loop re-screens the edited call before executing.
 *
 * <p>For {@code _LIKE_THIS} variants the {@code editedArgs} carry the call's args (used to
 * construct the grant's match key, with value positions wildcarded appropriately). For the legacy
 * {@code ACCEPT_AS_SESSION_RULE} / {@code ACCEPT_ONCE} aliases this is also non-null.
 *
 * @param option the chosen resolution option
 * @param editedArgs the user-overridden args (non-null for {@link VetoOption#EDIT} and the {@code
 *     _LIKE_THIS} grant-creating variants)
 * @param maskObservation whether the engine should apply {@code accept_and_mask} to the observation
 *     before it enters context. Defaults to {@code true} for approvals when the user did not
 *     explicitly choose otherwise.
 */
public record InterceptResolution(
        @NonNull VetoOption option, Map<String, Object> editedArgs, boolean maskObservation) {

    /** Compact constructor — back-compat for callers that do not specify masking. */
    public InterceptResolution(@NonNull VetoOption option, Map<String, Object> editedArgs) {
        this(option, editedArgs, true);
    }

    /**
     * Whether this resolution refuses the call (the agent gets a synthesized refusal observation).
     */
    public boolean isRefusal() {
        return option.isRefusal();
    }

    /** Whether the chosen option is the per-call refuse-and-continue variant. */
    public boolean isDeclineAndContinue() {
        return option.isDeclineAndContinue();
    }

    /** Whether the chosen option creates a permission grant. */
    public boolean createsGrant() {
        return option.createsGrant();
    }
}
