package top.focess.veto.api.credentials;

import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * Invocation-confined access to an imported secret. The host wipes the supplied copy on return.
 * This cooperative API is not a JVM sandbox: in-process plugin code can copy the array.
 * Implementations must not log or serialize the secret, lease, or callback failure details.
 */
public interface ImportedCredentialLease extends AutoCloseable {
    void use(@NonNull Consumer<char @NonNull []> operation);

    @Override
    void close();
}
