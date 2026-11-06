package top.focess.veto.secret;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserAuthenticatedEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Scopes secret-candidate availability to the userId, session, and agent transitions the host
 * broadcasts. A closed scope makes its captured references unrecoverable, so capture fails closed
 * until the userId is opened again.
 */
public final class SecretProtectionListener implements Listener {
    private final @NonNull SecretCandidateStore candidates;
    private final @NonNull SecretAgent agent;

    /** Creates the listener over the candidate store it scopes. */
    SecretProtectionListener(@NonNull SecretCandidateStore candidates, @NonNull SecretAgent agent) {
        this.candidates = candidates;
        this.agent = agent;
    }

    /** Protects text before the host commits it, preserving captured reference markers. */
    @EventHandler
    public void onTextCommit(@NonNull BeforeTextCommitEvent event) throws Exception {
        try {
            agent.protect(event);
        } catch (Throwable failure) {
            // The event bus contains listener failures; cancellation must still stop publication.
            event.setCancelled(true);
            throw failure;
        }
    }

    @EventHandler
    public void onUserAuthenticated(@NonNull UserAuthenticatedEvent event) {
        candidates.openUser(event.scope().userId());
    }

    @EventHandler
    public void onUserLogout(@NonNull UserLogoutEvent event) {
        candidates.closeUser(event.scope().userId());
        agent.discardUser(event.scope().userId());
    }

    @EventHandler
    public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
        candidates.retireSession(event.scope().userId(), event.scope().session());
        agent.discardSession(event.scope());
    }

    @EventHandler
    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
        candidates.discardAgent(event.scope());
        agent.discard(event.scope());
    }
}
