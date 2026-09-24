package top.focess.veto.api.credentials;

import org.jspecify.annotations.NonNull;

/**
 * Trusted host storage port, never a model-facing tool. Implementations persist credentials locally
 * and return opaque identifiers. The host must authorize the caller before supplying this port to a
 * candidate import.
 */
public interface CredentialWriter {
    boolean isUnlocked(@NonNull String owner);

    /** Receives plaintext only at the authorized local storage boundary. */
    @NonNull String createImportedCredential(
            @NonNull String owner,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull String value);
}
