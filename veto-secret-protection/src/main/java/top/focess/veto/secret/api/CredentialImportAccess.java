package top.focess.veto.secret.api;

import org.jspecify.annotations.NonNull;

/**
 * Trusted host authorization boundary, specific to this plugin. Hosts (Veto or other agent clients)
 * deliver an implementation through the generic plugin-context host-service lookup; no secret
 * values leave the local storage port.
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
