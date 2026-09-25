package top.focess.veto.api.credentials;

import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * Invocation-confined access to an imported secret. The host wipes the supplied copy on return.
 * This cooperative API is not a JVM sandbox: in-process plugin code can copy the array.
 * Implementations must not log or serialize the secret, lease, or callback failure details.
 */
public interface ImportedCredentialLease extends AutoCloseable {
    /**
     * Uses a short-lived copy of the secret, which the host wipes after the callback returns.
     *
     * @param operation synchronous callback receiving the temporary character array
     */
    void use(@NonNull Consumer<char @NonNull []> operation);

    /** Closes the lease and revokes further use. */
    @Override
    void close();
}
