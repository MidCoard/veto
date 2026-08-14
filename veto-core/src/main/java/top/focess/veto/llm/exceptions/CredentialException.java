package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * A credential problem detected before the provider call: the credential key is unset, no note
 * exists under it in the vault, or the vault is locked. Distinct from {@link LlmAuthException} (the
 * provider rejected the credential) so user-facing error mapping can point at the vault instead of
 * the provider. Never retryable (inherited).
 */
@SuppressWarnings("serial")
public class CredentialException extends LlmAuthException {

    /**
     * Constructs a new CredentialException with the specified message.
     *
     * @param message the detail message
     */
    public CredentialException(@NonNull String message) {
        super(message);
    }

    /**
     * Constructs a new CredentialException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public CredentialException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
