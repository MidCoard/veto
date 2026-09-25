package top.focess.veto.api.credentials;

import org.jspecify.annotations.NonNull;

/**
 * Invocation-bound host credential import authorization. Hosts (Veto or other agent clients)
 * deliver an implementation through the generic plugin-context host-service lookup; the returned
 * writer must revalidate the same approved invocation before storing a value.
 */
@FunctionalInterface
public interface CredentialImportAccess {
    /**
     * Host-verified owner, session, agent, and writer for one approved import.
     *
     * @param ownerId authenticated owner identifier
     * @param sessionId selected session identifier
     * @param agentId invoking agent identifier
     * @param writer invocation-bound credential writer
     */
    record Authorization(
            @NonNull String ownerId,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull CredentialWriter writer) {}

    /**
     * Resolves an approved import and binds a writer to its invocation.
     *
     * @param reference candidate import reference
     * @param service destination service label
     * @param label user-visible credential label
     * @return host-verified import authorization
     */
    @NonNull Authorization authorize(
            @NonNull String reference, @NonNull String service, @NonNull String label);
}
