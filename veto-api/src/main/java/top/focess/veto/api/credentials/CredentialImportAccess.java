package top.focess.veto.api.credentials;

import org.jspecify.annotations.NonNull;

/**
 * Invocation-bound host credential import authorization. Hosts (Veto or other agent clients)
 * deliver an implementation through the generic plugin-context host-service lookup; the returned
 * writer must revalidate the same approved invocation before storing a value.
 */
@FunctionalInterface
public interface CredentialImportAccess {
    record Authorization(
            @NonNull String ownerId,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull CredentialWriter writer) {}

    @NonNull Authorization authorize(
            @NonNull String reference, @NonNull String service, @NonNull String label);
}
