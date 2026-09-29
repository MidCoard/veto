package top.focess.veto.api.credentials;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Host-granted access to an owner's encrypted vault for a currently admitted tool invocation. */
public interface VaultAccess {
    /**
     * Opens a handle bound to the current authorized invocation.
     *
     * @param arguments arguments this plugin received for the approved call
     * @return a handle that rechecks the same invocation before each operation
     */
    @NonNull Scope open(@NonNull Map<@NonNull String, ?> arguments);

    /** An invocation-bound vault handle, never an authorization token for a later call. */
    interface Scope {
        /**
         * Returns the authenticated owner bound to this invocation.
         *
         * @return authenticated owner of this invocation
         */
        @NonNull String owner();

        /**
         * Returns the selected session bound to this invocation.
         *
         * @return selected session of this invocation
         */
        @NonNull String sessionId();

        /**
         * Returns the executing agent bound to this invocation.
         *
         * @return executing agent of this invocation
         */
        @NonNull String agentId();

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
