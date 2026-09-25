package top.focess.veto.api.credentials;

import org.jspecify.annotations.NonNull;

/**
 * Trusted host storage port, never a model-facing tool. Implementations persist credentials locally
 * and return opaque identifiers. The host must authorize the caller before supplying this port to a
 * candidate import.
 */
public interface CredentialWriter {
    /**
     * Checks whether the owner's credential store currently accepts writes.
     *
     * @param owner authenticated owner identifier
     * @return whether the store is unlocked
     */
    boolean isUnlocked(@NonNull String owner);

    /**
     * Receives plaintext only at the authorized local storage boundary.
     *
     * @param owner authenticated owner identifier
     * @param reference approved import reference
     * @param service destination service label
     * @param label user-visible credential label
     * @param value plaintext credential to store
     * @return opaque identifier for the stored credential
     */
    @NonNull String createImportedCredential(
            @NonNull String owner,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull String value);
}
