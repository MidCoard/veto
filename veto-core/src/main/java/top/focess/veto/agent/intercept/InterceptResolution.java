package top.focess.veto.agent.intercept;

import java.util.Map;

/**
 * The user's resolution of a veto pause, delivered via the veto endpoint and used to complete the
 * parked {@link java.util.concurrent.CompletableFuture}. For {@link VetoOption#EDIT} the user
 * rewrites the call's args; the loop re-screens the edited call before executing.
 *
 * @param option the chosen resolution option
 * @param editedArgs the user-overridden args (non-null only for {@link VetoOption#EDIT})
 */
public record InterceptResolution(VetoOption option, Map<String, Object> editedArgs) {

    /**
     * Whether this resolution refuses the call (the agent gets a synthesized refusal observation).
     */
    public boolean isRefusal() {
        return option == VetoOption.READ_DENY
                || option == VetoOption.ABORT_WRITE
                || option == VetoOption.BLOCK
                || option == VetoOption.DECLINE
                || option == VetoOption.DECLINE_AND_CONTINUE;
    }
}
