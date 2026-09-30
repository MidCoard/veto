package top.focess.veto.api.credentials;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** Host-granted access to an owner's encrypted vault for a currently admitted tool invocation. */
public interface VaultAccess {
    /**
     * Opens a handle bound to the current authorized invocation.
     *
     * @param arguments arguments this plugin received for the approved call
     * @return a handle that rechecks the same invocation before each operation
     */
    @NonNull Handle open(@NonNull Map<@NonNull String, ?> arguments);

    /** An invocation-bound vault handle, never an authorization token for a later call. */
    interface Handle {
        /**
         * Returns the host-attributed identity of this invocation after rechecking admission.
         * The returned identity is not a transferable vault authorization.
         *
         * @return authenticated owner, session, and agent
         */
        Scope.@NonNull AgentScope scope();

        /**
         * Reports whether this owner's vault currently accepts writes.
         *
         * @return whether the owner's vault currently accepts writes
         */
        boolean isUnlocked();

        /**
         * Creates a secure note, or returns an existing note with exactly the same title,
         * attributes, and body. A conflicting note is rejected.
         *
         * @param title stable note title
         * @param attributes note metadata owned by the calling plugin
         * @param body secret note body
         * @return opaque vault handle
         */
        @NonNull String createSecureNote(
                @NonNull String title,
                @NonNull Map<@NonNull String, @NonNull String> attributes,
                @NonNull String body);
    }
}
